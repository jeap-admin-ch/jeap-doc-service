package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.port.GovernanceImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.GovernanceImportRepository;
import ch.admin.bit.jeap.doc.domain.port.GovernanceIndexFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Imports the systems and the rules of the Governance Service's {@code /docs-api}: reads both indexes
 * conditionally, and fetches only the system or rule resources that are new or whose own entity tag moved.
 * <p>
 * Unlike the architecture model, there is no environment: the Governance Service is read once per instance.
 * Unlike an architecture artifact, a resource is JSON read directly rather than content behind a URL out of an
 * index - but the shape of the replication is the same, because the reason for it is the same: almost nothing
 * changes between two runs, and the upstream tags every resource so that finding that out costs one small
 * conditional request instead of the whole payload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GovernanceImport {

    private final GovernanceUpstream upstream;
    private final GovernanceResourceRepository resources;
    private final GovernanceImportRepository imports;
    private final GovernanceImportMetrics metrics;
    private final Clock clock;

    /**
     * Imports the systems and then the rules, and reports whether the stored state of either is complete.
     * <p>
     * <b>A build is asked for whenever either kind is complete</b>, changed or not. A build over unchanged
     * content costs a digest and is skipped, and asking only after a change would lose the request for good if
     * the trigger failed once: the next import would find both indexes unchanged. Both indexes also carry the
     * time of the last completed scoring run, which rebuilding keeps right on the overviews.
     * <p>
     * A kind whose import failed, or could not replicate every entry, keeps what it had and does not count.
     *
     * @return whether a build should be asked for
     */
    public boolean run() {
        if (!upstream.isConfigured()) {
            return false;
        }
        boolean systemsChanged = replicate(GovernanceResourceKind.SYSTEM, GovernanceResourceKind.SYSTEMS_INDEX,
                upstream::systemsIndex, upstream::system);
        boolean rulesChanged = replicate(GovernanceResourceKind.RULE, GovernanceResourceKind.RULES_INDEX,
                upstream::rulesIndex, upstream::rule);
        return systemsChanged || rulesChanged;
    }

    private boolean replicate(GovernanceResourceKind kind, GovernanceResourceKind indexKind,
                              Function<String, Optional<GovernanceIndexFetch>> index,
                              BiFunction<String, String, GovernanceResourceFetch> content) {
        Instant startedAt = clock.instant();
        GovernanceImportState before = imports.state(kind);
        try {
            Optional<GovernanceIndexFetch> fetched = index.apply(before.conditionalIndexEtag());
            if (fetched.isEmpty()) {
                log.debug("The {} index of the governance import is unchanged ({} stored).", kind,
                        before.itemCount());
                recordOutcome(kind, before, startedAt, GovernanceImportOutcome.UNCHANGED, null, true,
                        before.itemCount());
                return true;
            }
            return replicateEntries(kind, indexKind, before, startedAt, fetched.get(), content);
        } catch (GovernanceUnavailableException e) {
            // ERROR rather than the architecture import's WARN for the same shape of failure: the Governance
            // Service is a dependency this service alarms on being unreachable, and the message
            // names the resource and the upstream's own answer - built into e.getMessage() - so the alert and
            // the log line say the same thing.
            log.error("The {} of the governance import were not replicated: {} What is stored is kept.", kind,
                    e.getMessage());
            recordOutcome(kind, before, startedAt, GovernanceImportOutcome.FAILED, e.getMessage(), false,
                    before.itemCount());
            return false;
        } catch (RuntimeException e) {
            // Anything the writes throw - a constraint the upstream data violates, a database that went away.
            log.error("The {} of the governance import could not be stored. What is stored is kept.", kind, e);
            recordOutcome(kind, before, startedAt, GovernanceImportOutcome.FAILED, e.getMessage(), false,
                    before.itemCount());
            return false;
        }
    }

    private boolean replicateEntries(GovernanceResourceKind kind, GovernanceResourceKind indexKind,
                                  GovernanceImportState before, Instant startedAt, GovernanceIndexFetch index,
                                  BiFunction<String, String, GovernanceResourceFetch> content) {
        Map<String, GovernanceResourceRef> listed = byKey(index.entries());
        Map<String, GovernanceResourceRef> stored = byKey(resources.findRefs(kind));

        if (listed.isEmpty() && !stored.isEmpty()) {
            // The floor under the prune. An index that lists nothing where something is stored would delete
            // every resource of this kind in one run, and an upstream that answers wrongly looks exactly like
            // one that has genuinely withdrawn every one of them.
            String reason = ("The %s index lists none while %d are stored. Nothing is removed.")
                    .formatted(kind, stored.size());
            log.warn("{} An index that lists nothing is not believed: the stored copies are kept and the "
                     + "index is asked again on the next run.", reason);
            recordOutcome(kind, before, startedAt, GovernanceImportOutcome.FAILED, reason, false,
                    before.itemCount());
            return false;
        }

        // Everything is read before anything is written: a fetch that fails, or an entry that cannot be
        // replicated, then leaves what is stored exactly as it was.
        Fetched fetched = fetchWhatMoved(kind, index.entries(), stored, content);
        List<GovernanceResourceRef> gone = stored.entrySet().stream()
                .filter(entry -> !listed.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        if (fetched.skipped() > 0) {
            keepWhatIsStored(kind, before, startedAt, fetched.skipped(), listed.size());
            return false;
        }

        // The index is what a page's links are generated from, so it goes in with the resources it lists, in
        // one transaction: an index whose scoring time already says "now" is never stored beside pages that
        // are one run behind it.
        resources.replace(new GovernanceResource(indexKind, null, index.etag(), index.body(),
                index.body().length, startedAt), fetched.stored(), gone);
        fetched.confirmed().forEach(key -> resources.confirm(kind, key, clock.instant()));
        if (!gone.isEmpty()) {
            log.info("Removed {} {}(s) no longer listed by the Governance Service: {}.", gone.size(), kind,
                    gone.stream().map(GovernanceResourceRef::key).toList());
        }

        metrics.items(kind, "stored", fetched.stored().size());
        metrics.items(kind, "unchanged", fetched.confirmed().size());
        metrics.items(kind, "removed", gone.size());
        metrics.items(kind, "skipped", 0);
        report(kind, fetched, gone.size(), listed.size(), startedAt);

        GovernanceImportOutcome outcome = fetched.stored().isEmpty() && gone.isEmpty()
                ? GovernanceImportOutcome.UNCHANGED : GovernanceImportOutcome.CHANGED;
        recordOutcome(kind, before.withIndexEtag(index.etag()), startedAt, outcome, null, true, listed.size());
        return true;
    }

    /**
     * An entry that could not be replicated holds the whole kind at its last complete state. Applying the rest
     * would put new scores beside old detail pages, and counting the run as a success would hide the entry from
     * the alarm on the age of the last success.
     */
    private void keepWhatIsStored(GovernanceResourceKind kind, GovernanceImportState before, Instant startedAt,
                                  int skipped, int listed) {
        String reason = "%d of the %d %ss listed could not be replicated.".formatted(skipped, listed, kind);
        log.warn("{} Nothing of this run is stored. They are offered again on the next run; the index is asked "
                 + "unconditionally until they are.", reason);
        metrics.items(kind, "skipped", skipped);
        recordOutcome(kind, before, startedAt, GovernanceImportOutcome.PARTIAL, reason, false,
                before.itemCount());
    }

    /**
     * Fetches every resource whose entity tag moved. Writes nothing.
     */
    private Fetched fetchWhatMoved(GovernanceResourceKind kind, List<GovernanceResourceRef> index,
                                   Map<String, GovernanceResourceRef> stored,
                                   BiFunction<String, String, GovernanceResourceFetch> content) {
        List<GovernanceResource> fetched = new ArrayList<>();
        List<String> confirmed = new ArrayList<>();
        int skipped = 0;
        for (GovernanceResourceRef entry : index) {
            GovernanceResourceRef known = stored.get(entry.key());
            if (entry.hasSameContentAs(known)) {
                confirmed.add(entry.key());
                continue;
            }
            switch (content.apply(entry.key(), known == null ? null : known.etag())) {
                case GovernanceResourceFetch.Stored(GovernanceResource resource) -> fetched.add(resource);
                case GovernanceResourceFetch.Unchanged() when known != null -> confirmed.add(entry.key());
                case GovernanceResourceFetch.Unchanged() -> skipped++;
                case GovernanceResourceFetch.Skipped(String reason) -> {
                    log.debug("The {} '{}' is not replicated: {}", kind, entry.key(), reason);
                    skipped++;
                }
            }
        }
        return new Fetched(fetched, confirmed, skipped);
    }

    /**
     * A run that changed nothing is {@code DEBUG} on purpose: most runs change nothing, and a line per run per
     * kind saying so is a line nobody reads.
     */
    private void report(GovernanceResourceKind kind, Fetched fetched, int removed, int itemCount,
                        Instant startedAt) {
        if (fetched.stored().isEmpty() && removed == 0) {
            log.debug("The {}s of the governance import are unchanged ({} stored).", kind, itemCount);
        } else {
            log.info("Imported the {}s of the Governance Service: {} stored, {} unchanged, {} removed ({}).",
                    kind, fetched.stored().size(), fetched.confirmed().size(), removed,
                    Duration.between(startedAt, clock.instant()));
        }
    }

    /**
     * @param before the state to record, already carrying the index tag this run decided on - null where it
     *               is not trusted after a partial replication, kept as it was where the index itself could
     *               not be read at all (see the two call sites)
     */
    private void recordOutcome(GovernanceResourceKind kind, GovernanceImportState before, Instant startedAt,
                               GovernanceImportOutcome outcome, String failureReason, boolean indexTrusted,
                               int itemCount) {
        Instant now = clock.instant();
        boolean succeeded = outcome == GovernanceImportOutcome.CHANGED
                             || outcome == GovernanceImportOutcome.UNCHANGED;
        imports.save(new GovernanceImportState(kind, before.indexEtag(), indexTrusted, itemCount, now,
                succeeded ? now : before.lastSuccessAt(), outcome, failureReason));
        metrics.imported(kind, outcome, Duration.between(startedAt, now), itemCount);
    }

    private static Map<String, GovernanceResourceRef> byKey(List<GovernanceResourceRef> refs) {
        Map<String, GovernanceResourceRef> byKey = new LinkedHashMap<>();
        refs.forEach(ref -> byKey.put(ref.key(), ref));
        return byKey;
    }

    /** What one pass over an index read: what is to be stored, what is confirmed current, and what failed. */
    private record Fetched(List<GovernanceResource> stored, List<String> confirmed, int skipped) {
    }
}
