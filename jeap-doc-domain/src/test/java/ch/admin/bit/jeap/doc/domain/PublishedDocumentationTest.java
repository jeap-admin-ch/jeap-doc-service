package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishedDocumentationTest {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;

    @Mock
    private DocumentationBuildRepository builds;
    @Mock
    private SitePublicationStorage storage;

    private MovableClock clock;
    private PublicationProperties properties;
    private PublishedDocumentation documentation;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(NOW);
        properties = new PublicationProperties();
        documentation = new PublishedDocumentation(builds, storage, properties, clock);
    }

    @Test
    void open_whenNothingIsPublished_thenNothingIsRead() {
        when(builds.published(SITE)).thenReturn(Optional.empty());

        assertThat(documentation.isPublished(SITE)).isFalse();
        assertThat(documentation.open(SITE, "index.html")).isEmpty();
        verify(storage, never()).open(any(), any());
    }

    @Test
    void open_thenReadFromTheCurrentBuildsPrefix() {
        published(42L);
        when(storage.open("default/42", "index.html")).thenReturn(Optional.of(object()));

        assertThat(documentation.open(SITE, "index.html")).isPresent();
    }

    /**
     * A file of a page is one request of many: asking the database for every one of them would make the cost of
     * serving a page proportional to how many assets it has.
     */
    @Test
    void open_whenReadAgainWithinTheRefreshInterval_thenTheDatabaseIsAskedOnce() {
        published(42L);
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "index.html");
        clock.advance(properties.getRefresh().dividedBy(2));
        documentation.open(SITE, "assets/js/main.js");

        verify(builds, times(1)).published(SITE);
    }

    /**
     * ...but not for ever: another instance publishes, and this one has to pick it up without being told.
     */
    @Test
    void open_whenTheRefreshIntervalHasPassed_thenWhatAnotherInstancePublishedIsPickedUp() {
        when(builds.published(SITE))
                .thenReturn(Optional.of(build(42L)))
                .thenReturn(Optional.of(build(43L)));
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "index.html");
        clock.advance(properties.getRefresh().plusSeconds(1));
        documentation.open(SITE, "index.html");

        verify(storage).open(eq("default/42"), any());
        verify(storage).open(eq("default/43"), any());
    }

    private void published(long buildId) {
        when(builds.published(SITE)).thenReturn(Optional.of(build(buildId)));
    }

    private static DocumentationBuild build(long id) {
        return new DocumentationBuild(id, SITE, BuildTrigger.SCHEDULE, BuildState.SUCCEEDED, NOW, NOW, "test",
                "default/" + id, 1, 1, 1, null);
    }

    private static StoredObject object() {
        return new StoredObject(new ByteArrayInputStream(new byte[0]), 0, "\"tag\"", "text/html");
    }

    /** A clock a test can move, so the refresh interval can be crossed without waiting for it. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
