package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which publication a path is served from, now that a site is published as several.
 */
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
        documentation = new PublishedDocumentation(builds, new DocumentationSites(new SiteProperties()),
                new SystemSitePartition(new TwoSystems()), storage, properties, clock);
    }

    /**
     * A site whose shell has never been built has no front page and no navigation, so it counts as not
     * published - which is answered differently from a page that does not exist.
     */
    @Test
    void open_whenNothingIsPublished_thenNothingIsRead() {
        when(builds.publishedPartsOf(SITE)).thenReturn(List.of());

        assertThat(documentation.isPublished(SITE)).isFalse();
        assertThat(documentation.open(SITE, "index.html")).isEmpty();
        verify(storage, never()).open(any(), any());
    }

    @Test
    void open_thenAPathIsReadFromThePublicationOfThePartThatOwnsIt() {
        published(part(SitePart.SHELL, 42L), part("system-orders", 43L));
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "systems/orders/system-architecture/intro/index.html");
        documentation.open(SITE, "index.html");
        documentation.open(SITE, "systems/index.html");

        verify(storage).open(eq("default/43"), eq("systems/orders/system-architecture/intro/index.html"));
        // The shell owns the site's own pages and everything no part claims - the systems index among them.
        verify(storage).open(eq("default/42"), eq("index.html"));
        verify(storage).open(eq("default/42"), eq("systems/index.html"));
    }

    /**
     * The environment trees of a system belong to the same part, which is the point of this axis: switching
     * environment on one system's page stays inside one publication.
     */
    @Test
    void open_thenEveryEnvironmentOfASystemIsServedFromOnePublication() {
        published(part(SitePart.SHELL, 42L), part("system-orders", 43L));
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "systems/orders/index.html");
        documentation.open(SITE, "dev/systems/orders/index.html");

        verify(storage).open(eq("default/43"), eq("systems/orders/index.html"));
        verify(storage).open(eq("default/43"), eq("dev/systems/orders/index.html"));
    }

    /**
     * The shared files of a site come from one prefix of their own, whichever part emitted them - it is what
     * lets two parts live under one base URL. See {@link SharedAssets}.
     */
    @Test
    void open_whenThePathIsAShared_thenItIsReadFromTheSharedPrefix() {
        published(part(SitePart.SHELL, 42L));
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "assets/js/main.a1b2c3.js");
        documentation.open(SITE, "img/logo.svg");

        verify(storage).open(eq("default/shared"), eq("assets/js/main.a1b2c3.js"));
        verify(storage).open(eq("default/shared"), eq("img/logo.svg"));
    }

    /**
     * A part that was published and whose files the retention has since removed holds nothing. Serving from it
     * would answer 404 for every one of its pages; the shell answers instead, and its own 404 page says so.
     */
    @Test
    void open_whenAPartsFilesAreGone_thenTheShellAnswers() {
        published(part(SitePart.SHELL, 42L),
                new PublishedPart("system-orders", null, NOW, "digest-of-orders", 12));
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "systems/orders/index.html");

        verify(storage).open(eq("default/42"), eq("systems/orders/index.html"));
    }

    /**
     * A file of a page is one request of many: asking the database for every one of them would make the cost of
     * serving a page proportional to how many assets it has.
     */
    @Test
    void open_whenReadAgainWithinTheRefreshInterval_thenTheDatabaseIsAskedOnce() {
        published(part(SitePart.SHELL, 42L));
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "index.html");
        clock.advance(properties.getRefresh().dividedBy(2));
        documentation.open(SITE, "assets/js/main.js");

        verify(builds, times(1)).publishedPartsOf(SITE);
    }

    /**
     * ...but not for ever: another instance publishes, and this one has to pick it up without being told.
     */
    @Test
    void open_whenTheRefreshIntervalHasPassed_thenWhatAnotherInstancePublishedIsPickedUp() {
        when(builds.publishedPartsOf(SITE))
                .thenReturn(List.of(part(SitePart.SHELL, 42L)))
                .thenReturn(List.of(part(SitePart.SHELL, 43L)));
        when(storage.open(any(), any())).thenReturn(Optional.of(object()));

        documentation.open(SITE, "index.html");
        clock.advance(properties.getRefresh().plusSeconds(1));
        documentation.open(SITE, "index.html");

        verify(storage).open(eq("default/42"), any());
        verify(storage).open(eq("default/43"), any());
    }

    private void published(PublishedPart... parts) {
        when(builds.publishedPartsOf(SITE)).thenReturn(List.of(parts));
    }

    private static PublishedPart part(String part, long buildId) {
        return new PublishedPart(part, "default/" + buildId, NOW, "digest-of-" + part, 12);
    }

    private static StoredObject object() {
        return new StoredObject(new ByteArrayInputStream(new byte[0]), 0, "\"tag\"", "text/html");
    }

    /** A landscape of two systems, so that a site has parts to resolve a path against. */
    private static final class TwoSystems implements ArchitectureModelSource {

        @Override
        public boolean isConfiguredFor(String environment) {
            return true;
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.of("https://archrepo.example.ch");
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return Optional.of(NOW);
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return ArchitectureSnapshot.empty();
        }

        @Override
        public List<String> systemSlugsOf(String environment) {
            return List.of("orders", "tariffs");
        }
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
