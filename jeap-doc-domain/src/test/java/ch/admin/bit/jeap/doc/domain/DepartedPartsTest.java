package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to a part its site no longer has.
 * <p>
 * The system axis reads the parts from the architecture model, so a decommissioned system takes its part with
 * it - and nothing else follows on its own. This is the sweep that finishes the job, and the two guards that
 * keep it from ever removing a live system: a long retention, and a site whose partition produces nothing but
 * the shell is left alone entirely.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepartedPartsTest {

    private static final Instant NOW = Instant.parse("2026-09-08T02:45:00Z");
    private static final String SITE = Site.DEFAULT_SITE;

    @Mock
    private DocumentationBuildRepository builds;
    @Mock
    private SitePublicationStorage publication;

    private final BuildProperties properties = new BuildProperties();
    private final StubPartition partition = new StubPartition();

    @BeforeEach
    void setUp() {
        partition.parts = new ArrayList<>(List.of(SitePart.SHELL, "system-orders", "system-shipping"));
    }

    /** Long gone: the partition no longer produces it, and it has published nothing for the retention. */
    @Test
    void removeWhatIsGone_thenTheObjectsAndTheRecordsOfADepartedPartAreRemoved() {
        publishes(published("system-catalog", SITE + "/12", NOW.minus(properties.getDepartedPartRetention())
                .minusSeconds(1)));
        when(builds.prefixesBeyondRetention(PartKey.of(SITE, "system-catalog"), 0))
                .thenReturn(List.of(SITE + "/9"));

        departedParts().removeWhatIsGone();

        verify(publication).delete(SITE + "/12");
        verify(publication).delete(SITE + "/9");
        verify(builds).forgetPart(PartKey.of(SITE, "system-catalog"));
    }

    /** A part the site still has is what the site is serving. Nothing about it is ever removed. */
    @Test
    void removeWhatIsGone_thenAPartTheSiteStillHasIsUntouched() {
        publishes(published("system-orders", SITE + "/12", NOW.minusSeconds(31_536_000)));

        departedParts().removeWhatIsGone();

        verify(publication, never()).delete(any());
        verify(builds, never()).forgetPart(any());
    }

    /**
     * The retention is the first guard: a part missing from the partition for a moment - which is what an
     * import replacing the model looks like - is not a part that has left.
     */
    @Test
    void removeWhatIsGone_thenAPartThatPublishedRecentlyIsLeftAlone() {
        publishes(published("system-catalog", SITE + "/12", NOW.minusSeconds(60)));

        departedParts().removeWhatIsGone();

        verify(publication, never()).delete(any());
        verify(builds, never()).forgetPart(any());
    }

    /**
     * <b>The second guard, and the one that matters most.</b> An import that failed and stored an empty
     * landscape leaves a partition of nothing but the shell - which must never read as every system of the site
     * having been decommissioned at once.
     */
    @Test
    void removeWhatIsGone_whenThePartitionHasOnlyTheShell_thenNothingIsRemoved() {
        partition.parts = new ArrayList<>(List.of(SitePart.SHELL));
        publishes(published("system-orders", SITE + "/12", NOW.minus(properties.getDepartedPartRetention())
                .minusSeconds(1)));

        departedParts().removeWhatIsGone();

        verify(publication, never()).delete(any());
        verify(builds, never()).forgetPart(any());
    }

    /** The objects go first: records naming objects that are gone serve "not generated yet", not a page. */
    @Test
    void removeWhatIsGone_whenTheObjectsCannotBeRemoved_thenTheRecordsStay() {
        publishes(published("system-catalog", SITE + "/12", NOW.minus(properties.getDepartedPartRetention())
                .minusSeconds(1)));
        doThrow(new IllegalStateException("the bucket said no")).when(publication).delete(SITE + "/12");

        departedParts().removeWhatIsGone();

        verify(builds, never()).forgetPart(any());
    }

    /** An operator who knows a system is gone does not wait out the retention. */
    @Test
    void removeNow_thenADepartedPartIsRemovedWithoutWaiting() {
        publishes(published("system-catalog", SITE + "/12", NOW.minusSeconds(60)));

        assertThat(departedParts().removeNow(site(), "system-catalog"))
                .isEqualTo(DepartedParts.Removal.REMOVED);
        verify(publication).delete(SITE + "/12");
        verify(builds).forgetPart(PartKey.of(SITE, "system-catalog"));
    }

    /**
     * <b>And never a part the site still has.</b> It is what makes the endpoint safe to expose: a mistyped part
     * must not be able to take a live system's documentation off the site.
     */
    @Test
    void removeNow_whenTheSiteStillHasThePart_thenNothingIsRemoved() {
        publishes(published("system-orders", SITE + "/12", NOW.minusSeconds(60)));

        assertThat(departedParts().removeNow(site(), "system-orders"))
                .isEqualTo(DepartedParts.Removal.STILL_A_PART);
        verify(publication, never()).delete(any());
        verify(builds, never()).forgetPart(any());
    }

    /** A part nothing was ever published for, and no record of: there is nothing to answer with but a 404. */
    @Test
    void removeNow_whenThereIsNothingToRemove_thenItSaysSo() {
        publishes();
        when(builds.forgetPart(any())).thenReturn(0);

        assertThat(departedParts().removeNow(site(), "system-catalog"))
                .isEqualTo(DepartedParts.Removal.NOTHING_TO_REMOVE);
    }

    /** Records without a publication are still this part's, and removing them is what makes it gone. */
    @Test
    void removeNow_whenOnlyRecordsAreLeft_thenTheyAreRemoved() {
        publishes();
        when(builds.forgetPart(PartKey.of(SITE, "system-catalog"))).thenReturn(4);

        assertThat(departedParts().removeNow(site(), "system-catalog"))
                .isEqualTo(DepartedParts.Removal.REMOVED);
    }

    private void publishes(PublishedPart... parts) {
        when(builds.publishedPartsOf(SITE)).thenReturn(List.of(parts));
    }

    private static PublishedPart published(String part, String prefix, Instant publishedAt) {
        return new PublishedPart(part, prefix, publishedAt, "digest", 42);
    }

    private Site site() {
        return new DocumentationSites(new SiteProperties()).find(SITE).orElseThrow();
    }

    private DepartedParts departedParts() {
        return new DepartedParts(new DocumentationSites(new SiteProperties()), partition, builds, publication,
                properties, new OneAtATimeExclusiveWork(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The axis is not what is under test here, so the parts of a site are simply said. */
    private static final class StubPartition implements SitePartition {

        private List<String> parts = List.of();

        @Override
        public String axis() {
            return "stub";
        }

        @Override
        public List<SitePart> partsOf(Site site) {
            return parts.stream().map(id -> partOf(site, id).orElseThrow()).toList();
        }

        @Override
        public Optional<SitePart> partOf(Site site, String partId) {
            if (SitePart.SHELL.equals(partId)) {
                return Optional.of(shellOf(site));
            }
            return Optional.of(new SitePart(PartKey.of(site.id(), partId), partId, "", false,
                    site.environments().stream().map(SiteEnvironment::id).toList(), List.of()));
        }

        @Override
        public SitePart shellOf(Site site) {
            return new SitePart(PartKey.shellOf(site.id()), "the site itself", "", false,
                    site.environments().stream().map(SiteEnvironment::id).toList(), List.of());
        }

        @Override
        public List<SitePart> partsDocumenting(Site site, String environment, String systemName) {
            return List.of();
        }
    }
}
