package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The nightly clean-up asks for one delete, over everything that finished before the retention.
 * <p>
 * Which rows survive it is the repository's rule and is asserted against a real database in
 * {@code DocumentationBuildRepositoryAdapterIT}: a keep-set assembled here could not name the publication of a
 * part the configuration no longer has.
 */
@ExtendWith(MockitoExtension.class)
class DocumentationBuildHousekeepingTest {

    private static final Instant NOW = Instant.parse("2026-08-25T02:45:00Z");

    @Mock
    private DocumentationBuildRepository builds;

    @Test
    void removeOldBuilds_thenTheRecordsOlderThanTheRetentionGo() {
        when(builds.deleteFinishedBefore(any())).thenReturn(3);

        housekeeping().removeOldBuilds();

        ArgumentCaptor<Instant> finishedBefore = ArgumentCaptor.forClass(Instant.class);
        verify(builds).deleteFinishedBefore(finishedBefore.capture());
        assertThat(finishedBefore.getValue()).isEqualTo(NOW.minus(new BuildProperties().getHistoryRetention()));
    }

    /** Nothing is read to decide what to keep - the clean-up is the one statement. */
    @Test
    void removeOldBuilds_thenNoBuildIsReadToDecideWhatToKeep() {
        housekeeping().removeOldBuilds();

        verify(builds).deleteFinishedBefore(any());
        verifyNoMoreInteractions(builds);
    }

    private DocumentationBuildHousekeeping housekeeping() {
        return new DocumentationBuildHousekeeping(builds, new BuildProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC), new DirectExclusiveWork());
    }
}
