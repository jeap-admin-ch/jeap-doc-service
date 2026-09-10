package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Replicates the reaction graphs of one kind of one environment: the systems', the components' or the message
 * types'.
 * <p>
 * <b>The artifact step's twin</b>, and deliberately so: an index of entity tags, a prune of what it no longer
 * lists, a fetch of what moved, and a confirmation of what did not. Two things are this step's own.
 * <p>
 * <b>Every name is resolved against the stored model first</b> ({@link ReactionNameResolver}), because the
 * observer's names are not the model's - and the resolution happens <i>before</i> anything is compared, which
 * is what makes the prune do double duty: a system that has left the model stops being listed and its graph
 * goes, so there is no orphan sweep to run at the end of a model import.
 * <p>
 * <b>A message type is one request per variant.</b> The observer answers every variant of a type at once and
 * tags the whole answer, so a type with three variants whose tag moved is fetched three times and stored three
 * times. It is a handful of duplicate payloads on the rare type that has variants, served from the observer's
 * own in-memory snapshot, and the alternative is a step that groups its work by upstream resource - which is
 * one upstream's shape leaking into the domain.
 */
@Slf4j
public class ReactionImportStep implements ArchitectureImportStep {

    /** How many distinct unresolved names one warning spells out, before it says how many more there were. */
    private static final int NAMES_IN_A_WARNING = 10;

    private final ArchitectureImportKind kind;
    private final ReactionGraphUpstream upstream;
    private final ReactionGraphRepository graphs;
    private final ArchitectureModelSource models;
    private final ArchitectureImportRepository imports;
    private final ArchitectureImportMetrics metrics;
    private final Clock clock;

    public ReactionImportStep(ArchitectureImportKind kind, ReactionGraphUpstream upstream,
                              ReactionGraphRepository graphs, ArchitectureModelSource models,
                              ArchitectureImportRepository imports, ArchitectureImportMetrics metrics,
                              Clock clock) {
        this.kind = kind;
        this.upstream = upstream;
        this.graphs = graphs;
        this.models = models;
        this.imports = imports;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public ArchitectureImportKind kind() {
        return kind;
    }

    @Override
    public ImportOutcome run(String environment, Deadline deadline) {
        if (!upstream.isConfiguredFor(environment)) {
            // No reaction observer for this environment, or none for this instance at all. Nothing is called
            // and nothing is recorded: an environment without reactions is a landscape, not a failure.
            return ImportOutcome.NOT_CONFIGURED;
        }
        Instant startedAt = clock.instant();
        ArchitectureImportState before = imports.state(environment, kind);
        try {
            // The index is asked conditionally only after a run that got through its whole list. A "not
            // modified" says the observer's graphs are unchanged, not that everything it lists was stored.
            Optional<Fetched<List<ReactionGraphRef>>> index =
                    upstream.index(environment, kind, before.conditionalIndexEtag());
            if (index.isEmpty()) {
                log.debug("The {} of the environment {} are unchanged ({} stored).", kind, environment,
                        before.itemCount());
                return recordOutcome(environment, before, startedAt, ImportOutcome.UNCHANGED, null, true,
                        before.itemCount());
            }
            return replicate(environment, before, startedAt, deadline, index.get());
        } catch (ArchitectureModelUnavailableException e) {
            log.warn("The {} of the environment {} were not replicated: {} What is stored is kept.",
                    kind, environment, e.getMessage());
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage(), false,
                    before.itemCount());
        } catch (RuntimeException e) {
            // Anything the writes throw - a constraint an upstream entry violates, a database that went away.
            log.error("The {} of the environment {} could not be stored. What is stored is kept.",
                    kind, environment, e);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage(), false,
                    before.itemCount());
        }
    }

    private ImportOutcome replicate(String environment, ArchitectureImportState before, Instant startedAt,
                                    Deadline deadline, Fetched<List<ReactionGraphRef>> index) {
        Resolved resolved = resolve(environment, index.value());
        Map<Identity, ReactionGraphRef> listed = byIdentity(resolved.refs());
        Map<Identity, ReactionGraphRef> stored = byIdentity(graphs.findRefs(environment, kind));

        if (listed.isEmpty() && !stored.isEmpty()) {
            // The floor under the prune, the artifact step's. An index that lists nothing where something is
            // stored would delete a whole environment's graphs in one run, and everything that produces it
            // looks the same from here: a proxy, a truncated answer, an observer that has not built its first
            // snapshot - and, for this step, a model import that failed, because a landscape with no systems
            // resolves no names at all.
            String reason = ("The index of the %s of the environment %s lists none this service could place "
                             + "while %d are stored. Nothing is removed.")
                    .formatted(kind, environment, stored.size());
            log.warn("{} An index that lists nothing is not believed: the stored graphs are kept and the index "
                     + "is asked again on the next run.", reason);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, reason, false,
                    before.itemCount());
        }

        if (resolved.refs().size() != listed.size()) {
            // Two entries the observer spells differently and the model spells the same are one row here, so
            // one of them is what gets stored. Fetching both would write the row twice and report a total the
            // counts can exceed.
            log.debug("The index of the {} of the environment {} lists {} entries the model places on {} "
                      + "graphs; the repeats are fetched once.",
                    kind, environment, resolved.refs().size(), listed.size());
        }
        int removed = prune(environment, listed, stored);
        Replicated replicated = fetchWhatMoved(environment, List.copyOf(listed.values()), stored, deadline,
                listed.size());

        metrics.items(environment, kind, "stored", replicated.fetched());
        metrics.items(environment, kind, "unchanged", replicated.unchanged());
        metrics.items(environment, kind, "skipped", replicated.skipped());
        metrics.items(environment, kind, "unresolved", resolved.unresolvedGraphs());
        report(environment, replicated, removed, listed.size(), startedAt);

        // The index tag is remembered only when the run left nothing behind: nothing skipped, and nothing the
        // model could not place. An unresolved name is not a failure of this run - the model may carry it
        // tomorrow - but a "not modified" on the index would hide it until the observer's graphs change.
        boolean indexTrusted = replicated.trustsTheIndex() && resolved.unresolved().isEmpty();
        String indexEtag = indexTrusted ? index.etag() : null;
        return recordOutcome(environment, before.withIndexEtag(indexEtag), startedAt,
                outcomeOf(replicated, removed), null, indexTrusted, listed.size());
    }

    /**
     * Every listed graph under the name the model uses, and the observer's names for the ones it could not
     * place.
     * <p>
     * The model is read once per run rather than per entry: it is the stored landscape of the environment, the
     * model step of this same import has just replaced it, and an index lists hundreds of names.
     */
    private Resolved resolve(String environment, List<ReactionGraphRef> listed) {
        ReactionNameResolver resolver = new ReactionNameResolver(models.read(environment).model());
        List<ReactionGraphRef> refs = new ArrayList<>();
        // By name and not by graph: a message type is listed once per variant, so one type the model does not
        // have is eighty-one entries here, and a line repeating it eighty-one times says nothing about how
        // much of the index it is.
        Map<String, Integer> unresolved = new LinkedHashMap<>();
        for (ReactionGraphRef entry : listed) {
            resolver.resolve(entry)
                    .ifPresentOrElse(refs::add, () -> unresolved.merge(entry.upstreamName(), 1, Integer::sum));
        }
        if (!unresolved.isEmpty()) {
            // Once per run at WARN, with names rather than a bare count: this is the failure that otherwise
            // produces no graph and no error, and the first import of a real landscape wants it read.
            log.warn("The {} of the environment {}: {} of the names the observer lists are not in the stored "
                     + "model and are not imported, covering {} of {} graphs - {}{}, each with the number of "
                     + "graphs it covers. A system is matched by name or alias ignoring case, a component by "
                     + "name within the system its reactions were published under.",
                    kind, environment, unresolved.size(), graphsOf(unresolved), listed.size(),
                    spell(unresolved),
                    unresolved.size() > NAMES_IN_A_WARNING
                            ? " and %d more".formatted(unresolved.size() - NAMES_IN_A_WARNING) : "");
        }
        return new Resolved(refs, unresolved);
    }

    private static int graphsOf(Map<String, Integer> unresolved) {
        return unresolved.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The first names of a warning, each with how many graphs it covers. */
    private static String spell(Map<String, Integer> unresolved) {
        return unresolved.entrySet().stream()
                .limit(NAMES_IN_A_WARNING)
                .map(entry -> "%s (%d)".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Deletes what the index no longer lists, and reports how many that was. Right after the index and not at
     * the end, so that a run which later runs out of time has still pruned correctly.
     */
    private int prune(String environment, Map<Identity, ReactionGraphRef> listed,
                      Map<Identity, ReactionGraphRef> stored) {
        List<ReactionGraphRef> gone = stored.entrySet().stream()
                .filter(entry -> !listed.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (gone.isEmpty()) {
            return 0;
        }
        graphs.remove(gone);
        metrics.items(environment, kind, "removed", gone.size());
        return gone.size();
    }

    /**
     * Fetches every graph whose entity tag moved, until the list is done or the deadline is.
     */
    private Replicated fetchWhatMoved(String environment, List<ReactionGraphRef> listed,
                                      Map<Identity, ReactionGraphRef> stored, Deadline deadline, int count) {
        int fetched = 0;
        int unchanged = 0;
        int skipped = 0;
        for (ReactionGraphRef entry : inFetchOrder(listed, stored)) {
            if (deadline.hasExpired()) {
                // A shutdown at INFO: it is what every deployment does to the import that is running. A
                // deadline that ran out at WARN: that one is a landscape past its budget.
                String message = "The replication of the {} of the environment {} stopped after {} of {}: {}. "
                                 + "What was stored is kept.";
                if (deadline.isBecauseOfShutdown()) {
                    log.info(message, kind, environment, fetched + unchanged + skipped, count,
                            deadline.reason());
                } else {
                    log.warn(message, kind, environment, fetched + unchanged + skipped, count,
                            deadline.reason());
                }
                return new Replicated(fetched, unchanged, skipped, false);
            }
            ReactionGraphRef known = stored.get(Identity.of(entry));
            if (entry.hasSameContentAs(known)) {
                graphs.confirm(environment, kind, entry.name(), entry.system(), entry.variant(),
                        clock.instant());
                unchanged++;
                continue;
            }
            switch (upstream.content(environment, entry, known == null ? null : known.etag())) {
                case GraphFetch.Stored(ReactionGraph graph) -> {
                    graphs.store(StoredReactionGraph.of(entry, graph, clock.instant()));
                    fetched++;
                }
                case GraphFetch.Unchanged() when known != null -> {
                    // The index listed a tag that moved and the graph answered "not modified" against the
                    // stored one: the index was ahead of the graph, or behind it. Either way what is stored is
                    // what the observer serves.
                    graphs.confirm(environment, kind, entry.name(), entry.system(), entry.variant(),
                        clock.instant());
                    unchanged++;
                }
                // A "not modified" with nothing stored to be unmodified against cannot be confirmed. It is
                // left for the next run, like anything else that could not be replicated.
                case GraphFetch.Unchanged() -> skipped++;
                case GraphFetch.Skipped(String reason) -> {
                    log.debug("The {} of {} in the environment {} is not replicated: {}", kind, entry.name(),
                            environment, reason);
                    skipped++;
                }
            }
        }
        return new Replicated(fetched, unchanged, skipped, true);
    }

    private static ImportOutcome outcomeOf(Replicated replicated, int removed) {
        if (!replicated.complete()) {
            return ImportOutcome.PARTIAL;
        }
        return replicated.fetched() == 0 && removed == 0 ? ImportOutcome.UNCHANGED : ImportOutcome.REPLACED;
    }

    /**
     * A run that changed nothing is {@code DEBUG} on purpose: the observer rebuilds its graphs once a day, so
     * most runs change nothing and a line per run per environment and kind saying so is a line nobody reads.
     */
    private void report(String environment, Replicated replicated, int removed, int count,
                        Instant startedAt) {
        if (replicated.fetched() == 0 && removed == 0 && replicated.skipped() == 0) {
            log.debug("The {} of the environment {} are unchanged ({} stored).", kind, environment, count);
        } else {
            log.info("Replicated the {} of the environment {}: {} stored, {} unchanged, {} removed, {} not "
                     + "replicated ({}).", kind, environment, replicated.fetched(), replicated.unchanged(),
                    removed, replicated.skipped(), Duration.between(startedAt, clock.instant()));
        }
        if (replicated.skipped() > 0) {
            log.warn("{} of the {} of the environment {} could not be replicated and are offered again on the "
                     + "next run; the index is asked unconditionally until they are. The reasons are logged "
                     + "above.", replicated.skipped(), kind, environment);
        }
    }

    /**
     * What has to be fetched first, then what only has to be confirmed - and that oldest confirmation first.
     * A run that keeps hitting its deadline then keeps making progress instead of reconfirming the same first
     * entries and never reaching a changed one at the end of the alphabet.
     */
    private static List<ReactionGraphRef> inFetchOrder(List<ReactionGraphRef> listed,
                                                       Map<Identity, ReactionGraphRef> stored) {
        List<ReactionGraphRef> ordered = new ArrayList<>(listed);
        ordered.sort(Comparator
                .comparing((ReactionGraphRef ref) -> ref.hasSameContentAs(stored.get(Identity.of(ref))))
                .thenComparing(ref -> checkedAtOf(stored.get(Identity.of(ref))),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ReactionGraphRef::name)
                .thenComparing(ReactionGraphRef::variant));
        return ordered;
    }

    private static Instant checkedAtOf(ReactionGraphRef stored) {
        return stored == null ? null : stored.checkedAt();
    }

    private ImportOutcome recordOutcome(String environment, ArchitectureImportState before, Instant startedAt,
                                        ImportOutcome outcome, String failureReason, boolean indexTrusted,
                                        int itemCount) {
        Instant now = clock.instant();
        // A run that stopped at its deadline is not a success: the staleness gauge is what an operator alarms
        // on, and a replication truncating at item twenty of five hundred must not read healthy.
        boolean succeeded = outcome == ImportOutcome.REPLACED || outcome == ImportOutcome.UNCHANGED;
        imports.save(new ArchitectureImportState(environment, kind, null, before.indexEtag(), indexTrusted,
                itemCount, now, succeeded ? now : before.lastSuccessAt(), outcome, failureReason));
        metrics.imported(environment, kind, outcome, Duration.between(startedAt, now), itemCount);
        return outcome;
    }

    private static Map<Identity, ReactionGraphRef> byIdentity(List<ReactionGraphRef> refs) {
        Map<Identity, ReactionGraphRef> byIdentity = new LinkedHashMap<>();
        refs.forEach(ref -> byIdentity.put(Identity.of(ref), ref));
        return byIdentity;
    }

    /** What one pass over the index did, and whether it got to the end of it. */
    private record Replicated(int fetched, int unchanged, int skipped, boolean complete) {

        /** Whether the index tag may be sent on the next run: everything it listed is stored. */
        boolean trustsTheIndex() {
            return complete && skipped == 0;
        }
    }

    /**
     * What the model could place, and the observer's names for what it could not - each with the number of
     * graphs listed under it.
     */
    private record Resolved(List<ReactionGraphRef> refs, Map<String, Integer> unresolved) {

        /**
         * How many graphs the unresolved names cover. What the meter counts, so that it stays comparable with
         * the ones beside it: those count graphs, and a message type is one name over many of them.
         */
        int unresolvedGraphs() {
            return unresolved.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * What one graph is addressed by, <b>folded the way the unique index on the table folds it</b>: the name
     * and the system lower-cased, the variant as it stands. So what is one key here is one row there - and a
     * variant that differs only in case is two graphs in both places, because that is what it is upstream.
     * <p>
     * <b>The system is part of it</b>, and only a component's graph has one: two systems may each call a
     * component {@code gateway}, and one key for both would store one of the two and draw it on both pages.
     */
    private record Identity(String name, String system, String variant) {

        static Identity of(ReactionGraphRef ref) {
            return new Identity(ref.name().toLowerCase(Locale.ROOT), ref.system().toLowerCase(Locale.ROOT),
                    ref.variant());
        }
    }
}
