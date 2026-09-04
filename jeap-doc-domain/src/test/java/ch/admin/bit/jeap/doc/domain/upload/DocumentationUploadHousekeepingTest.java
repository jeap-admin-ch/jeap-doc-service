package ch.admin.bit.jeap.doc.domain.upload;

import ch.admin.bit.jeap.doc.domain.DirectExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentationUploadHousekeepingTest {

    private static final Instant NOW = Instant.parse("2026-08-25T02:30:00Z");

    @Mock
    private DocumentationUploadRepository uploadRepository;

    @Test
    void removeOldUploads_thenEverythingReceivedBeforeTheRetentionIsRemoved() {
        when(uploadRepository.deleteReceivedBefore(any())).thenReturn(3);

        housekeeping(new UploadProperties()).removeOldUploads();

        ArgumentCaptor<Instant> receivedBefore = ArgumentCaptor.forClass(Instant.class);
        verify(uploadRepository).deleteReceivedBefore(receivedBefore.capture());
        assertThat(receivedBefore.getValue()).isEqualTo(NOW.minus(Duration.ofDays(14)));
    }

    @Test
    void removeOldUploads_whenTheRetentionIsConfigured_thenItIsTheOneThatCounts() {
        UploadProperties properties = new UploadProperties();
        properties.getHousekeeping().setRetention(Duration.ofDays(30));
        when(uploadRepository.deleteReceivedBefore(any())).thenReturn(0);

        housekeeping(properties).removeOldUploads();

        verify(uploadRepository).deleteReceivedBefore(NOW.minus(Duration.ofDays(30)));
    }

    private DocumentationUploadHousekeeping housekeeping(UploadProperties properties) {
        return new DocumentationUploadHousekeeping(uploadRepository, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), new DirectExclusiveWork());
    }
}
