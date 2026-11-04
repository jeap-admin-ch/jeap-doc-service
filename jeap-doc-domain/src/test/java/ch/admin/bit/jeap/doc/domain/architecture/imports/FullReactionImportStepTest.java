package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.port.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.FULL_REACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FullReactionImportStepTest {
    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    private final ReactionGraphUpstream upstream = mock(ReactionGraphUpstream.class);
    private final ReactionGraphRepository graphs = mock(ReactionGraphRepository.class);
    private final ArchitectureImportRepository imports = mock(ArchitectureImportRepository.class);
    private final ArchitectureImportMetrics metrics = mock(ArchitectureImportMetrics.class);
    private final FullReactionImportStep step = new FullReactionImportStep(upstream, graphs, imports, metrics,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(upstream.isConfiguredFor("dev")).thenReturn(true);
        when(imports.state("dev", FULL_REACTIONS)).thenReturn(ArchitectureImportState.none("dev", FULL_REACTIONS));
    }

    @Test
    void replacesTheWholeSnapshotWithoutReadingAnySubgraph() {
        byte[] bytes = {1, 2, 3};
        when(upstream.fullGraph("dev", null, null)).thenReturn(GraphFetch.stored(new ReactionGraph("tag", "fingerprint", bytes, 3)));
        assertThat(step.run("dev", Deadline.none())).isEqualTo(ImportOutcome.REPLACED);
        verify(graphs).store(new StoredReactionGraph("dev", FULL_REACTIONS, "", "", "", "", "tag", "fingerprint", bytes, 3, NOW));
        verify(upstream, never()).index(anyString(), any(), any());
        verify(graphs, never()).remove(any());
    }

    @Test
    void conditionallyConfirmsTheStoredSnapshot() {
        stored();
        when(upstream.fullGraph("dev", "old-tag", "")).thenReturn(GraphFetch.unchanged());
        assertThat(step.run("dev", Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);
        verify(graphs).confirm("dev", FULL_REACTIONS, "", "", "", NOW);
        verify(graphs, never()).store(any());
    }

    @Test
    void invalidUpdateKeepsTheLastSuccessAndStoredGraph() {
        stored();
        when(upstream.fullGraph("dev", "old-tag", "")).thenReturn(GraphFetch.skipped("Invalid median"));
        assertThat(step.run("dev", Deadline.none())).isEqualTo(ImportOutcome.FAILED);
        verify(graphs, never()).store(any());
        verify(graphs, never()).remove(any());
        verify(imports).save(argThat(state -> state.lastSuccessAt().equals(NOW.minusSeconds(60))
                && state.lastOutcome() == ImportOutcome.FAILED));
    }

    @Test
    void emptyReplacementDoesNotEraseObservations() {
        stored();
        when(upstream.fullGraph("dev", "old-tag", "")).thenReturn(GraphFetch.stored(new ReactionGraph("new", "new", new byte[0], 0)));
        assertThat(step.run("dev", Deadline.none())).isEqualTo(ImportOutcome.FAILED);
        verify(graphs, never()).store(any());
    }

    @Test
    void firstEmptySnapshotIsLegitimate() {
        when(upstream.fullGraph("dev", null, null)).thenReturn(GraphFetch.stored(new ReactionGraph("empty", "empty", new byte[0], 0)));
        assertThat(step.run("dev", Deadline.none())).isEqualTo(ImportOutcome.REPLACED);
        verify(graphs).store(argThat(graph -> graph.drawableNodes() == 0));
    }

    @Test
    void firstFailureRecordsNoSuccessfulImport() {
        when(upstream.fullGraph("dev", null, null)).thenReturn(GraphFetch.skipped("Unavailable"));
        assertThat(step.run("dev", Deadline.none())).isEqualTo(ImportOutcome.FAILED);
        verify(imports).save(argThat(state -> state.lastSuccessAt() == null && state.itemCount() == 0));
        verify(graphs, never()).store(any());
    }

    @Test
    void stoppingDuringFetchDoesNotStoreTheReplacement() {
        boolean[] stopped = {false};
        when(upstream.fullGraph("dev", null, null)).thenAnswer(call -> {
            stopped[0] = true;
            return GraphFetch.stored(new ReactionGraph("new", "new", new byte[0], 1));
        });
        assertThat(step.run("dev", Deadline.of(Duration.ofMinutes(1), () -> stopped[0]))).isEqualTo(ImportOutcome.PARTIAL);
        verify(graphs, never()).store(any());
    }

    @Test
    void disabledObserverDoesNothing() {
        assertThat(step.run("prod", Deadline.none())).isEqualTo(ImportOutcome.NOT_CONFIGURED);
        verifyNoInteractions(graphs, imports, metrics);
    }

    private void stored() {
        when(graphs.findRefs("dev", FULL_REACTIONS)).thenReturn(List.of(new ReactionGraphRef("dev", FULL_REACTIONS,
                "", "", "", "", "old-tag", null, NOW.minusSeconds(60), true)));
        when(imports.state("dev", FULL_REACTIONS)).thenReturn(new ArchitectureImportState("dev", FULL_REACTIONS,
                null, null, true, 1, NOW.minusSeconds(60), NOW.minusSeconds(60), ImportOutcome.REPLACED, null));
    }
}
