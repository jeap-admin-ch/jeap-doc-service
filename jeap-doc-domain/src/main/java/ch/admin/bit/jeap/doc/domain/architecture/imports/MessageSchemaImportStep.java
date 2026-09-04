package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream;
import ch.admin.bit.jeap.doc.domain.port.SchemaFetch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replicates the Avro schemas of the message type versions of one environment.
 * <p>
 * <b>A version is revalidated, not fetched once and trusted.</b> It rarely moves - a changed schema is normally
 * published as a new version - but the compatibility a version declares is derived upstream from the version
 * list, so publishing an intermediate version changes what an already published version answers, and an import
 * re-renders the schemas. A run therefore asks about every version the index lists: a new one costs a payload,
 * one that is unchanged costs a {@code 304} and no body, which is the same trade the artifacts make.
 * <p>
 * Like the artifacts and unlike the model, a run that gets part way through <b>keeps what it stored</b>. The
 * versions are independent of one another and the ones not reached are taken by the next run - and the order
 * is what makes that work: what is not stored first, then what only has to be confirmed, oldest first.
 */
@Slf4j
@RequiredArgsConstructor
public class MessageSchemaImportStep implements ArchitectureImportStep {

    private final MessageSchemaUpstream upstream;
    private final MessageSchemaRepository schemas;
    private final ArchitectureImportRepository imports;
    private final ArchitectureImportMetrics metrics;
    private final Clock clock;

    @Override
    public ArchitectureImportKind kind() {
        return ArchitectureImportKind.MESSAGE_SCHEMA;
    }

    @Override
    public ImportOutcome run(String environment, Deadline deadline) {
        Instant startedAt = clock.instant();
        ArchitectureImportState before = imports.state(environment, kind());
        try {
            // Unconditional, and there is nothing to make it conditional with: the index lists no entity tag
            // per version, and a tag over the list as a whole would not answer whether a version's schemas
            // moved - which is the question this step asks. The tags it revalidates with come from the store.
            List<MessageVersionRef> listed = upstream.index(environment);
            return replicate(environment, before, startedAt, deadline, listed);
        } catch (ArchitectureModelUnavailableException e) {
            log.warn("The message schemas of the environment {} were not replicated: {} What is stored is "
                     + "kept.", environment, e.getMessage());
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage(), false,
                    before.itemCount());
        } catch (RuntimeException e) {
            // Anything the writes throw. It has to reach the state row and the meter all the same, or the
            // staleness gauge reads healthy for an environment whose replication has been failing all day.
            log.error("The message schemas of the environment {} could not be stored. What is stored is kept.",
                    environment, e);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage(), false,
                    before.itemCount());
        }
    }

    private ImportOutcome replicate(String environment, ArchitectureImportState before, Instant startedAt,
                                    Deadline deadline, List<MessageVersionRef> listed) {
        Map<String, MessageVersionRef> wanted = byIdentity(listed);
        List<MessageVersionRef> stored = schemas.findRefs(environment);

        int removed = prune(environment, wanted, stored, listed);
        Fetched fetched = revalidate(environment, wanted.values(), byIdentity(stored), deadline);

        metrics.items(environment, kind(), "stored", fetched.stored());
        metrics.items(environment, kind(), "unchanged", fetched.unchanged());
        metrics.items(environment, kind(), "skipped", fetched.skipped());
        report(environment, fetched, removed, wanted.size(), startedAt);

        if (fetched.stoppedEarly()) {
            // A shutdown at INFO: it is what every deployment does to the import that is running, and there is
            // nothing to act on. A deadline that ran out at WARN: that one is a landscape that has grown past
            // its budget, and the next run picks up where this one stopped.
            String message = "The replication of the message schemas of the environment {} stopped after {} of "
                             + "{} versions: {}. What was stored is kept.";
            if (deadline.isBecauseOfShutdown()) {
                log.info(message, environment, fetched.reached(), wanted.size(), deadline.reason());
            } else {
                log.warn(message, environment, fetched.reached(), wanted.size(), deadline.reason());
            }
            return recordOutcome(environment, before, startedAt, ImportOutcome.PARTIAL,
                    "The replication stopped after %d of %d versions: %s."
                            .formatted(fetched.reached(), wanted.size(), deadline.reason()),
                    false, wanted.size());
        }
        // What "complete" claims is that the run got through its whole list and stored or confirmed every entry
        // in it - so a run that skipped one is not complete, whatever else it managed. The run that stopped
        // early already returned above with the same answer, and the artifact step computes it the same way.
        boolean complete = fetched.skipped() == 0;
        if (fetched.stored() == 0 && fetched.unchanged() == 0 && removed == 0 && fetched.skipped() > 0) {
            // Nothing was replicated at all while there was something to replicate. Reporting that as a
            // success would refresh lastSuccessAt and reset the staleness gauge for an environment whose every
            // version failed - which is the one thing the gauge is there to show. A run that skipped some of
            // its versions and got the rest is a success: one version nobody can fetch must not have the
            // gauge climbing for the whole environment, for ever.
            return recordOutcome(environment, before, startedAt, ImportOutcome.PARTIAL,
                    "None of the %d version(s) of the index were replicated: %s."
                            .formatted(wanted.size(), fetched.couldNotBeFetched()),
                    false, wanted.size());
        }
        ImportOutcome outcome = fetched.stored() > 0 || removed > 0
                ? ImportOutcome.REPLACED : ImportOutcome.UNCHANGED;
        return recordOutcome(environment, before, startedAt, outcome, null, complete, wanted.size());
    }

    /**
     * Removes the stored versions the index no longer lists - right after the index rather than at the end, so
     * that a run which later runs out of its deadline has still pruned correctly.
     * <p>
     * <b>Only within the message types the index actually reports on.</b> The index is served from the
     * architecture repository's own store, so a run of <i>its</i> import that was itself partial answers with
     * fewer message types than exist - and treating an absent one as a deletion would throw away every schema
     * of it and fetch them all again on the next run. A message type the index does not mention at all is
     * therefore left alone; one it mentions without a version this service holds has that version removed,
     * which is what a withdrawn version looks like.
     */
    private int prune(String environment, Map<String, MessageVersionRef> wanted,
                      List<MessageVersionRef> stored, List<MessageVersionRef> listed) {
        Set<String> messageTypesInTheIndex = new LinkedHashSet<>();
        listed.forEach(ref -> messageTypesInTheIndex.add(messageTypeOf(ref)));
        List<MessageVersionRef> gone = stored.stream()
                .filter(ref -> messageTypesInTheIndex.contains(messageTypeOf(ref)))
                .filter(ref -> !wanted.containsKey(keyOf(ref)))
                .toList();
        if (gone.isEmpty()) {
            return 0;
        }
        schemas.remove(gone);
        // Counted here rather than with the others: remove is its own transaction, so a failure in the fetch
        // that follows would otherwise lose the count of what has already gone.
        metrics.items(environment, kind(), "removed", gone.size());
        log.info("{} message type version(s) of the environment {} are no longer published and were removed.",
                gone.size(), environment);
        return gone.size();
    }

    /**
     * Asks about every listed version while the deadline holds: the ones not stored for their schemas, the
     * ones stored for a confirmation that they have not moved.
     * <p>
     * The set of what is held grows as the run stores, because the upstream answers with the system and the
     * message type as <i>it</i> spells them: two index entries can resolve to one stored row, and a second
     * store of it would be a second insert against the unique index.
     */
    private Fetched revalidate(String environment, Collection<MessageVersionRef> listed,
                               Map<String, MessageVersionRef> stored, Deadline deadline) {
        Set<String> storedInThisRun = new LinkedHashSet<>();
        List<String> couldNotBeFetched = new ArrayList<>();
        int fetched = 0;
        int unchanged = 0;
        int skipped = 0;
        int reached = 0;
        int withoutATag = 0;
        for (MessageVersionRef ref : inFetchOrder(listed, stored)) {
            if (deadline.hasExpired()) {
                return new Fetched(fetched, unchanged, skipped, reached, true, withoutATag, couldNotBeFetched);
            }
            reached++;
            if (storedInThisRun.contains(keyOf(ref))) {
                // Another entry of the index resolved to this row a moment ago. Asking again would fetch the
                // same version twice and store it twice.
                unchanged++;
                continue;
            }
            MessageVersionRef known = stored.get(keyOf(ref));
            switch (upstream.version(environment, ref, known == null ? null : known.etag())) {
                case SchemaFetch.Stored(MessageVersionSchemas version) -> {
                    if (store(environment, ref, version, storedInThisRun)) {
                        withoutATag++;
                    }
                    fetched++;
                }
                case SchemaFetch.Unchanged() when known != null -> {
                    schemas.confirm(environment, known.system(), known.message(), known.version(),
                            clock.instant());
                    unchanged++;
                }
                // A "not modified" with nothing stored to be unmodified against is not something that can be
                // confirmed. It is left for the next run, like anything else that could not be replicated.
                case SchemaFetch.Unchanged() -> {
                    couldNotBeFetched.add(ref.identity());
                    skipped++;
                }
                case SchemaFetch.Skipped(String reason) -> {
                    log.debug("The schemas of {} of the system {} in the environment {} are not replicated: {}",
                            ref.message() + " " + ref.version(), ref.system(), environment, reason);
                    couldNotBeFetched.add(ref.identity());
                    skipped++;
                }
            }
        }
        return new Fetched(fetched, unchanged, skipped, reached, false, withoutATag, couldNotBeFetched);
    }

    /**
     * Stores one fetched version and remembers both spellings it is now held under.
     *
     * @return true when the upstream answered without an ETag, so the next run cannot confirm it cheaply
     */
    private boolean store(String environment, MessageVersionRef ref, MessageVersionSchemas version,
                          Set<String> storedInThisRun) {
        String storedAs = keyOf(version);
        if (!storedAs.equals(keyOf(ref))) {
            // The index spelled the system or the message type one way and the upstream stores it another -
            // an alias, or a different case. Both index entries are this one row.
            log.debug("The index of the environment {} lists {}; the upstream stores it as {}.",
                    environment, ref.identity(), storedAs);
        }
        schemas.store(version);
        storedInThisRun.add(keyOf(ref));
        storedInThisRun.add(storedAs);
        return version.etag() == null || version.etag().isBlank();
    }

    /**
     * What has to be fetched first, then what only has to be confirmed - and that in the order it was
     * confirmed, oldest first.
     * <p>
     * A run that keeps hitting its deadline then keeps making progress: every run spends its time on the
     * versions that are new, and the confirmations of the ones that are not rotate through the list instead of
     * reconfirming the same first twenty and never reaching a new one at the end of the alphabet.
     */
    private static List<MessageVersionRef> inFetchOrder(Collection<MessageVersionRef> listed,
                                                        Map<String, MessageVersionRef> stored) {
        List<MessageVersionRef> ordered = new ArrayList<>(listed);
        ordered.sort(Comparator
                .comparing((MessageVersionRef ref) -> stored.containsKey(keyOf(ref)))
                .thenComparing(ref -> checkedAtOf(stored.get(keyOf(ref))),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(MessageVersionRef::system)
                .thenComparing(MessageVersionRef::message)
                .thenComparing(MessageVersionRef::version));
        return ordered;
    }

    private static Instant checkedAtOf(MessageVersionRef stored) {
        return stored == null ? null : stored.checkedAt();
    }

    /** What a run did, at the one level an operator reads. */
    private void report(String environment, Fetched fetched, int removed, int listed, Instant startedAt) {
        if (fetched.stored() == 0 && fetched.skipped() == 0 && removed == 0) {
            log.debug("The message schemas of the environment {} are unchanged ({} versions).", environment,
                    listed);
            return;
        }
        log.info("Replicated the message schemas of the environment {}: {} stored, {} unchanged, {} removed, "
                 + "{} skipped, of {} versions ({}).", environment, fetched.stored(), fetched.unchanged(),
                removed, fetched.skipped(), listed, Duration.between(startedAt, clock.instant()));
        if (fetched.skipped() > 0) {
            // Named, not just counted. What is not stored is asked about before what only needs confirming, so
            // a version that can never be fetched keeps its place at the head of every run - and where enough
            // of them fill the deadline, nothing behind them is ever reached. The names are what says which.
            log.warn("{} message type version(s) of the environment {} were not replicated and are offered "
                     + "again on the next run: {}. A version that never succeeds holds its place at the front "
                     + "of every run.", fetched.skipped(), environment, fetched.couldNotBeFetched());
        }
        if (fetched.withoutATag() > 0) {
            // Stored without a tag, so the next run asks for it unconditionally and stores it again - for
            // ever, and reporting REPLACED each time. Nothing else would say why.
            log.warn("{} message type version(s) of the environment {} came back without an entity tag. They "
                     + "cannot be revalidated, so they are fetched in full on every run.", fetched.withoutATag(),
                    environment);
        }
    }

    private ImportOutcome recordOutcome(String environment, ArchitectureImportState before, Instant startedAt,
                                        ImportOutcome outcome, String failureReason, boolean complete,
                                        int itemCount) {
        Instant now = clock.instant();
        boolean succeeded = outcome == ImportOutcome.REPLACED || outcome == ImportOutcome.UNCHANGED;
        // No content hash and no index tag: neither is used by this kind, and a value nothing reads is a value
        // somebody later believes.
        imports.save(new ArchitectureImportState(environment, kind(), null, null, complete, itemCount, now,
                succeeded ? now : before.lastSuccessAt(), outcome, failureReason));
        metrics.imported(environment, kind(), outcome, Duration.between(startedAt, now), itemCount);
        return outcome;
    }

    /**
     * How this run decides that two things are the same version, and it is <b>folded</b>.
     * <p>
     * The index of the upstream and the rows of the store carry the spellings of two different exports of the
     * same upstream - an alias or a differently-cased path resolves to the stored spelling - so comparing them
     * exactly leaves a stored version unrecognised: it is fetched in full on every run, never confirmed, and
     * never pruned, because the message type it belongs to is not in the folded index either. The unique index
     * of the table folds the same two names, so what is one key here is one row there.
     * <p>
     * Only the keys are folded. The values keep the spelling they came with, and {@code confirm} and
     * {@code remove} address the row with those, so nothing downstream sees a lower-cased name.
     */
    private static String keyOf(String system, String message, String version) {
        return system.toLowerCase(Locale.ROOT) + " " + message.toLowerCase(Locale.ROOT) + " " + version;
    }

    private static String keyOf(MessageVersionRef ref) {
        return keyOf(ref.system(), ref.message(), ref.version());
    }

    /** How a fetched version identifies itself, which is not always how the index spelled it. */
    private static String keyOf(MessageVersionSchemas version) {
        return keyOf(version.system(), version.message(), version.version());
    }

    /** What identifies the message type a version belongs to - see {@link #prune}. Folded, like the keys. */
    private static String messageTypeOf(MessageVersionRef ref) {
        return ref.system().toLowerCase(Locale.ROOT) + " " + ref.message().toLowerCase(Locale.ROOT);
    }

    private static Map<String, MessageVersionRef> byIdentity(List<MessageVersionRef> refs) {
        Map<String, MessageVersionRef> byIdentity = new LinkedHashMap<>();
        refs.forEach(ref -> byIdentity.put(keyOf(ref), ref));
        return byIdentity;
    }

    /**
     * @param stored       how many versions this run fetched and stored
     * @param unchanged    how many the upstream confirmed against the stored tag, costing no payload
     * @param skipped      how many it could not fetch, each of which is offered again next time
     * @param reached      how far down the list it got - stored, confirmed and unfetchable alike
     * @param stoppedEarly whether the deadline ended the run before the list did
     * @param withoutATag  how many arrived with no entity tag, which is how many will be fetched in full again
     * @param couldNotBeFetched which versions were left, by name - see {@link #report}
     */
    private record Fetched(int stored, int unchanged, int skipped, int reached, boolean stoppedEarly,
                           int withoutATag, List<String> couldNotBeFetched) {
    }
}
