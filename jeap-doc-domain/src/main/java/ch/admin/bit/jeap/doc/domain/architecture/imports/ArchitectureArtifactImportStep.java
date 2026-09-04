package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArtifactFetch;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
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

/**
 * Replicates one kind of artifact of one environment: the OpenAPI specifications or the database schemas.
 * <p>
 * Unlike the model, these are fetched one at a time and only where an entity tag moved. The upstream tags them
 * from a stored hash, so a conditional request costs it nothing, and almost none of them change between two
 * imports.
 * <p>
 * Also unlike the model, a run that gets part way through <b>keeps what it stored</b>. Artifacts are
 * independent of one another, each is its own transaction, and a run that stops early is progress rather than
 * nothing.
 */
@Slf4j
public class ArchitectureArtifactImportStep implements ArchitectureImportStep {

    private final ArchitectureImportKind kind;
    private final ArchitectureArtifactUpstream upstream;
    private final ArchitectureArtifactRepository artifacts;
    private final ArchitectureImportRepository imports;
    private final ArchitectureImportMetrics metrics;
    private final Clock clock;

    public ArchitectureArtifactImportStep(ArchitectureImportKind kind, ArchitectureArtifactUpstream upstream,
                                          ArchitectureArtifactRepository artifacts,
                                          ArchitectureImportRepository imports,
                                          ArchitectureImportMetrics metrics, Clock clock) {
        this.kind = kind;
        this.upstream = upstream;
        this.artifacts = artifacts;
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
        Instant startedAt = clock.instant();
        ArchitectureImportState before = imports.state(environment, kind);
        try {
            // The index is asked conditionally only after a run that got through its whole list. A "not
            // modified" says the landscape is unchanged, not that everything in it was fetched, and trusting
            // it after a truncated run would skip what was missed for ever.
            Optional<Fetched<List<ArchitectureArtifactRef>>> index =
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
            // It has to reach the state row and the meter all the same, or the staleness gauge reads healthy
            // for an environment whose replication has been failing since the last deployment.
            log.error("The {} of the environment {} could not be stored. What is stored is kept.",
                    kind, environment, e);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage(), false,
                    before.itemCount());
        }
    }

    private ImportOutcome replicate(String environment, ArchitectureImportState before, Instant startedAt,
                                    Deadline deadline, Fetched<List<ArchitectureArtifactRef>> index) {
        Map<Identity, ArchitectureArtifactRef> listed = byIdentity(index.value());
        Map<Identity, ArchitectureArtifactRef> stored = byIdentity(artifacts.findRefs(environment, kind));

        if (listed.isEmpty() && !stored.isEmpty()) {
            // The floor under the prune. An index that lists nothing where something is stored would delete
            // the whole environment's worth of artifacts in one run, and everything that can produce it - a
            // proxy, a truncated answer, an architecture repository whose own import failed - looks exactly
            // like an upstream that publishes nothing. An upstream that has genuinely withdrawn every one of
            // them is still withdrawing them on the next run, and refusing costs an operator one warning in
            // between. The answers that carry no index at all are refused before this, in the adapter.
            String reason = ("The index of the %s of the environment %s lists none while %d are stored. "
                             + "Nothing is removed.").formatted(kind, environment, stored.size());
            log.warn("{} An index that lists nothing is not believed: the stored copies are kept and the index "
                     + "is asked again on the next run.", reason);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, reason, false,
                    before.itemCount());
        }

        int removed = prune(environment, listed, stored);
        Replicated replicated = fetchWhatMoved(environment, index.value(), stored, deadline, listed.size());

        metrics.items(environment, kind, "stored", replicated.fetched());
        metrics.items(environment, kind, "unchanged", replicated.unchanged());
        metrics.items(environment, kind, "skipped", replicated.skipped());
        report(environment, replicated, removed, listed.size(), startedAt);

        // The index tag is only remembered when the run got through the whole list and left nothing behind
        // in it, which is what makes the conditional request of the next run safe: an entry that was skipped
        // has to be offered again, and a "not modified" on the index would hide it for as long as the index
        // stays the same.
        boolean indexTrusted = replicated.trustsTheIndex();
        String indexEtag = indexTrusted ? index.etag() : null;
        return recordOutcome(environment, before.withIndexEtag(indexEtag), startedAt,
                outcomeOf(replicated, removed), null, indexTrusted, listed.size());
    }

    /**
     * Deletes what the index no longer lists, and reports how many that was.
     * <p>
     * Right after the index and not at the end: this needs only the index, so a run that later runs out of time
     * has still pruned correctly. At the end, a truncated run would delete what it never got to.
     */
    private int prune(String environment, Map<Identity, ArchitectureArtifactRef> listed,
                      Map<Identity, ArchitectureArtifactRef> stored) {
        List<ArchitectureArtifactRef> gone = stored.entrySet().stream()
                .filter(entry -> !listed.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (gone.isEmpty()) {
            return 0;
        }
        artifacts.remove(gone);
        metrics.items(environment, kind, "removed", gone.size());
        return gone.size();
    }

    /**
     * Fetches every artifact whose entity tag moved, until the list is done or the deadline is.
     */
    private Replicated fetchWhatMoved(String environment, List<ArchitectureArtifactRef> index,
                                      Map<Identity, ArchitectureArtifactRef> stored, Deadline deadline,
                                      int listed) {
        int fetched = 0;
        int unchanged = 0;
        int skipped = 0;
        for (ArchitectureArtifactRef entry : inFetchOrder(index, stored)) {
            if (deadline.hasExpired()) {
                // A shutdown at INFO: it is what every deployment does to the import that is running, and
                // there is nothing to act on. A deadline that ran out at WARN: that one is a landscape that
                // has grown past its budget.
                String message = "The replication of the {} of the environment {} stopped after {} of {}: {}. "
                                 + "What was stored is kept.";
                if (deadline.isBecauseOfShutdown()) {
                    log.info(message, kind, environment, fetched + unchanged + skipped, listed,
                            deadline.reason());
                } else {
                    log.warn(message, kind, environment, fetched + unchanged + skipped, listed,
                            deadline.reason());
                }
                return new Replicated(fetched, unchanged, skipped, false);
            }
            ArchitectureArtifactRef known = stored.get(Identity.of(entry));
            if (entry.hasSameContentAs(known)) {
                artifacts.confirm(environment, kind, entry.system(), entry.component(), clock.instant());
                unchanged++;
                continue;
            }
            switch (upstream.content(environment, entry, known == null ? null : known.etag())) {
                case ArtifactFetch.Stored(ArchitectureArtifact artifact) -> {
                    artifacts.store(artifact);
                    fetched++;
                }
                case ArtifactFetch.Unchanged() when known != null -> {
                    // The index listed a tag that moved, and the content answered "not modified" against the
                    // stored one: the index was ahead of the content, or behind it. Either way the stored copy
                    // is what the upstream serves.
                    artifacts.confirm(environment, kind, entry.system(), entry.component(), clock.instant());
                    unchanged++;
                }
                // A "not modified" with nothing stored to be unmodified against is not something that can be
                // confirmed. It is left for the next run, like anything else that could not be replicated.
                case ArtifactFetch.Unchanged() -> skipped++;
                case ArtifactFetch.Skipped(String reason) -> {
                    log.debug("The {} of {}/{} in the environment {} is not replicated: {}", kind,
                            entry.system(), entry.component(), environment, reason);
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
     * A run that changed nothing is {@code DEBUG} on purpose: most runs change nothing, and a line per run per
     * environment and kind saying so is a line nobody reads.
     */
    private void report(String environment, Replicated replicated, int removed, int itemCount,
                        Instant startedAt) {
        if (replicated.fetched() == 0 && removed == 0 && replicated.skipped() == 0) {
            log.debug("The {} of the environment {} are unchanged ({} stored).", kind, environment, itemCount);
        } else {
            log.info("Replicated the {} of the environment {}: {} stored, {} unchanged, {} removed, {} not "
                     + "replicated ({}).", kind, environment, replicated.fetched(), replicated.unchanged(),
                    removed, replicated.skipped(), Duration.between(startedAt, clock.instant()));
        }
        if (replicated.skipped() > 0) {
            // Said at WARN once per run rather than once per artifact, so that an artifact the upstream serves
            // and this service refuses - one over the size cap - is noticed without filling the log with it.
            log.warn("{} of the {} of the environment {} could not be replicated and are offered again on the "
                     + "next run; the index is asked unconditionally until they are. The reasons are logged "
                     + "above.", replicated.skipped(), kind, environment);
        }
    }

    /**
     * What one pass over the index did, whether it got to the end of it, and how many entries it left behind
     * on the way - which is not the same thing.
     */
    private record Replicated(int fetched, int unchanged, int skipped, boolean complete) {

        /** Whether the index tag may be sent on the next run: everything the index listed is stored. */
        boolean trustsTheIndex() {
            return complete && skipped == 0;
        }
    }

    /**
     * What has to be fetched first, then what only has to be confirmed - and that in the order it was
     * confirmed, oldest first.
     * <p>
     * A run that keeps hitting its deadline then keeps making progress: every run spends its time on the
     * artifacts that changed or are new, and the confirmations of the ones that did not rotate through the
     * list instead of reconfirming the same first twenty entries and never reaching a changed one at the end
     * of the alphabet.
     */
    private static List<ArchitectureArtifactRef> inFetchOrder(List<ArchitectureArtifactRef> listed,
                                                              Map<Identity, ArchitectureArtifactRef> stored) {
        List<ArchitectureArtifactRef> ordered = new ArrayList<>(listed);
        ordered.sort(Comparator
                .comparing((ArchitectureArtifactRef ref) -> ref.hasSameContentAs(stored.get(Identity.of(ref))))
                .thenComparing(ref -> checkedAtOf(stored.get(Identity.of(ref))),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ArchitectureArtifactRef::system)
                .thenComparing(ArchitectureArtifactRef::component));
        return ordered;
    }

    private static Instant checkedAtOf(ArchitectureArtifactRef stored) {
        return stored == null ? null : stored.checkedAt();
    }

    /**
     * @param indexTrusted whether the next run may ask the index conditionally. A run that skipped an entry
     *                     may still have succeeded - an artifact over the size cap is the upstream's defect,
     *                     and the run replicated everything else - but the tag must not be trusted after it
     */
    private ImportOutcome recordOutcome(String environment, ArchitectureImportState before,
                                        Instant startedAt, ImportOutcome outcome, String failureReason,
                                        boolean indexTrusted, int itemCount) {
        Instant now = clock.instant();
        // A run that stopped at its deadline is not a success: the staleness gauge is what an operator
        // alarms on, and a replication truncating at item twenty of five hundred must not read healthy.
        boolean succeeded = outcome == ImportOutcome.REPLACED || outcome == ImportOutcome.UNCHANGED;
        imports.save(new ArchitectureImportState(environment, kind, null, before.indexEtag(), indexTrusted,
                itemCount, now, succeeded ? now : before.lastSuccessAt(), outcome, failureReason));
        metrics.imported(environment, kind, outcome, Duration.between(startedAt, now), itemCount);
        return outcome;
    }

    private static Map<Identity, ArchitectureArtifactRef> byIdentity(List<ArchitectureArtifactRef> refs) {
        Map<Identity, ArchitectureArtifactRef> byIdentity = new LinkedHashMap<>();
        refs.forEach(ref -> byIdentity.put(Identity.of(ref), ref));
        return byIdentity;
    }

    /**
     * What one artifact is addressed by, and it is <b>folded</b> - the way the unique index on the table folds
     * it, so that what is one key here is one row there. The two names are two fields rather than one joined
     * string: a system may be called {@code Order Fulfilment}, so any separator that can occur in a name makes
     * a system {@code a/b} with a component {@code c} indistinguishable from a system {@code a} with a
     * component {@code b/c} - and a prune would then take the neighbour with it.
     */
    private record Identity(String system, String component) {

        static Identity of(ArchitectureArtifactRef ref) {
            return new Identity(ref.system().toLowerCase(Locale.ROOT),
                    ref.component().toLowerCase(Locale.ROOT));
        }
    }
}
