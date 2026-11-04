package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Replaces one complete environment snapshot only after a successful fetch and validation. */
@Component
@RequiredArgsConstructor
@Slf4j
public class FullReactionImportStep implements ArchitectureImportStep {
    private final ReactionGraphUpstream upstream;
    private final ReactionGraphRepository graphs;
    private final ArchitectureImportRepository imports;
    private final ArchitectureImportMetrics metrics;
    private final Clock clock;

    @Override
    public ArchitectureImportKind kind() {
        return ArchitectureImportKind.FULL_REACTIONS;
    }

    @Override
    public ImportOutcome run(String environment, Deadline deadline) {
        if (!upstream.isConfiguredFor(environment)) {
            return ImportOutcome.NOT_CONFIGURED;
        }
        Instant startedAt = clock.instant();
        ArchitectureImportState before = imports.state(environment, kind());
        try {
            if (deadline.hasExpired()) {
                return outcome(environment, before, startedAt, ImportOutcome.PARTIAL, deadline.reason(), before.itemCount());
            }
            var known = graphs.findRefs(environment, kind()).stream().findFirst().orElse(null);
            GraphFetch fetched = upstream.fullGraph(environment, known == null ? null : known.etag(), known == null ? null : known.upstreamName());
            if (deadline.hasExpired()) {
                return outcome(environment, before, startedAt, ImportOutcome.PARTIAL, deadline.reason(), before.itemCount());
            }
            return switch (fetched) {
                case GraphFetch.Stored(ReactionGraph graph) -> {
                    if (!graph.isDrawable() && known != null && known.drawable()) {
                        throw new ArchitectureModelUnavailableException("An empty full graph cannot replace observed reactions");
                    }
                    graphs.store(new StoredReactionGraph(environment, kind(), "", "", "", graph.sourceFormat() == null ? "" : graph.sourceFormat(), graph.etag(),
                            graph.fingerprint(), graph.data(), graph.drawableNodes(), clock.instant()));
                    log.info("Imported the complete reaction graph of {} ({} nodes).", environment, graph.drawableNodes());
                    yield outcome(environment, before, startedAt, ImportOutcome.REPLACED, null, 1);
                }
                case GraphFetch.Unchanged() -> {
                    if (known == null) {
                        throw new ArchitectureModelUnavailableException("No stored full graph to confirm");
                    }
                    graphs.confirm(environment, kind(), "", "", "", clock.instant());
                    yield outcome(environment, before, startedAt, ImportOutcome.UNCHANGED, null, 1);
                }
                case GraphFetch.Skipped(String reason) -> throw new ArchitectureModelUnavailableException(reason);
            };
        } catch (ArchitectureModelUnavailableException e) {
            log.warn("The complete reaction graph of {} was not imported: {}. The stored graph is kept.", environment, e.getMessage());
            return outcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage(), before.itemCount());
        } catch (RuntimeException e) {
            log.error("The complete reaction graph of {} could not be stored.", environment, e);
            return outcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage(), before.itemCount());
        }
    }

    private ImportOutcome outcome(String environment, ArchitectureImportState before, Instant startedAt,
                                  ImportOutcome outcome, String reason, int count) {
        boolean succeeded = outcome == ImportOutcome.REPLACED || outcome == ImportOutcome.UNCHANGED;
        Instant now = clock.instant();
        imports.save(new ArchitectureImportState(environment, kind(), null, null, succeeded, count, now,
                succeeded ? now : before.lastSuccessAt(), outcome, reason));
        metrics.imported(environment, kind(), outcome, Duration.between(startedAt, now), count);
        return outcome;
    }
}
