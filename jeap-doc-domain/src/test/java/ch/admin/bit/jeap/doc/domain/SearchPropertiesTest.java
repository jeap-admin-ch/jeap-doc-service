package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What an instance may not be configured with, checked at startup rather than at the first index run. */
class SearchPropertiesTest {

    @Test
    void theDefaults_areAccepted() {
        assertThatCode(() -> new SearchProperties().check()).doesNotThrowAnyException();
    }

    @Test
    void aRunTakenForDeadWhileItIsStillAlive_isRefused() {
        SearchProperties properties = new SearchProperties();
        properties.setLockLease(Duration.ofMinutes(30));
        properties.setAbandonedAfter(Duration.ofMinutes(10));

        assertThatThrownBy(properties::check).hasMessageContaining("abandoned-after");
    }

    @Test
    void keepingOnlyTheCurrentIndex_isRefused() {
        SearchProperties properties = new SearchProperties();
        properties.setRetention(1);

        assertThatThrownBy(properties::check).hasMessageContaining("retention");
    }

    /** A microsite that contributes nothing is this feature switched off in a way nobody would find. */
    @Test
    void aMicrositeCapOfNothing_isRefused() {
        SearchProperties properties = new SearchProperties();
        properties.setMaxMicrositePages(0);

        assertThatThrownBy(properties::check).hasMessageContaining("max-microsite-pages");
    }

    @Test
    void aPageSizeThatCannotBeRead_isRefused() {
        SearchProperties tooLarge = new SearchProperties();
        tooLarge.setMaxMicrositePageBytes(DataSize.ofGigabytes(4));
        assertThatThrownBy(tooLarge::check).hasMessageContaining("max-microsite-page-bytes");

        SearchProperties nothing = new SearchProperties();
        nothing.setMaxMicrositePageBytes(DataSize.ofBytes(0));
        assertThatThrownBy(nothing::check).hasMessageContaining("max-microsite-page-bytes");
    }

    /** One page is read into a byte array, so what the bundle is asked for has to fit an int. */
    @Test
    void thePageSize_isHandedOnAsBytes() {
        assertThat(new SearchProperties().maxMicrositePageBytes()).isEqualTo(512 * 1024);
    }
}
