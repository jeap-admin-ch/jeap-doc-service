package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one way to ask for documentation to be published.
 * <p>
 * Everything that wants a rebuild comes through here, which is what makes the collapsing rule cover all of it.
 * What this asserts is that nothing gets past it, that a trigger naming a <i>thing</i> reaches the part that
 * carries it, and that a site which does not want to be published on upload is not.
 */
@ExtendWith(MockitoExtension.class)
class DocumentationBuildTriggerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T09:00:00Z");
    private static final PartKey ORDERS = PartKey.of(Site.DEFAULT_SITE, "system-orders");
    private static final PartKey SHELL = PartKey.shellOf(Site.DEFAULT_SITE);

    @Mock
    private DocumentationBuildRequestRepository requests;
    @Mock
    private DocumentationBuildPickup pickup;

    private DocumentationBuildTrigger trigger;
    private RecordingBuildMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new RecordingBuildMetrics();
        trigger = triggerFor(new SiteProperties());
    }

    /**
     * An upload names a system and no environment at all, and with a part per system it does not have to: the
     * one part that carries that system in every environment is what is asked for.
     */
    @Test
    void requestBecauseOfUpload_thenThePartOfThatSystemIsAskedFor() {
        trigger.requestBecauseOfUpload(Site.DEFAULT_SITE, "ORDERS");

        verify(requests).requestAll(eq(List.of(ORDERS)), eq(BuildTrigger.UPLOAD), eq(NOW), any(), eq(false));
    }

    /** The name is slugged the way the generator slugs it, so an upload reaches the part that holds its pages. */
    @Test
    void requestBecauseOfUpload_thenTheSystemNameIsSluggedTheWayThePathIs() {
        trigger.requestBecauseOfUpload(Site.DEFAULT_SITE, "Orders Intake");

        verify(requests).requestAll(eq(List.of(PartKey.of(Site.DEFAULT_SITE, "system-orders-intake"))),
                eq(BuildTrigger.UPLOAD), eq(NOW), isNull(), eq(false));
    }

    /**
     * <b>The import asks for the whole site.</b> Which of its systems moved is not asked: a part is one
     * system, and a part whose content has not moved is not generated - so the price of not knowing is the
     * content of every part, and not a site rebuilt.
     */
    @Test
    void requestBecauseTheArchitectureWasImported_thenEveryPartOfTheSiteIsAskedFor() {
        DocumentationBuildTrigger documented = triggerFor(new SiteProperties(), landscapeOf("orders", "tariffs"));

        int parts = documented.requestBecauseTheArchitectureWasImported("dev");

        assertThat(parts).isEqualTo(3);
        // One ask for the lot: the parts of a publication have to become owed in one transaction.
        ArgumentCaptor<List<PartKey>> asked = ArgumentCaptor.captor();
        verify(requests).requestAll(asked.capture(), eq(BuildTrigger.IMPORT), eq(NOW), any(), eq(false));
        assertThat(asked.getValue()).containsExactlyInAnyOrder(SHELL, ORDERS,
                PartKey.of(Site.DEFAULT_SITE, "system-tariffs"));
        // The gauge that answers "how many builds did that import set off".
        assertThat(metrics.triggered).containsExactly(Site.DEFAULT_SITE + ":IMPORT:3");
    }

    /**
     * An environment is not a site's own - one landscape can be documented by several sites - so every site
     * that has it is asked, and each of them reports what it asked for.
     */
    @Test
    void requestBecauseTheArchitectureWasImported_whenTwoSitesHaveThatEnvironment_thenBothAreAskedFor() {
        DocumentationBuildTrigger both = triggerFor(propertiesOf(Map.of(
                Site.DEFAULT_SITE, new SiteProperties.Site(), "governance", new SiteProperties.Site())),
                landscapeOf("orders", "tariffs"));

        assertThat(both.requestBecauseTheArchitectureWasImported("dev"))
                .describedAs("every part of both sites")
                .isEqualTo(6);
        // One ask per site, each carrying that site's parts.
        ArgumentCaptor<List<PartKey>> asked = ArgumentCaptor.captor();
        verify(requests, times(2)).requestAll(asked.capture(), eq(BuildTrigger.IMPORT), eq(NOW), any(),
                eq(false));
        assertThat(asked.getAllValues().stream().flatMap(List::stream))
                .contains(ORDERS, PartKey.of("governance", "system-orders"));
    }

    /** A landscape of an environment no site has is imported all the same, and asks for nothing. */
    @Test
    void requestBecauseTheArchitectureWasImported_whenNoSiteHasThatEnvironment_thenNothingIsAskedFor() {
        assertThat(trigger.requestBecauseTheArchitectureWasImported("an-environment-nobody-documents")).isZero();

        verify(requests, never()).requestAll(any(), any(), any(), any(), anyBoolean());
    }

    /**
     * A site published on a schedule only says so, and an upload to it must not start a build - otherwise the
     * setting would do nothing at all.
     */
    @Test
    void requestBecauseOfUpload_whenTheSiteIsNotPublishedOnUpload_thenNothingIsAskedFor() {
        SiteProperties.Site configured = new SiteProperties.Site();
        configured.setPublishOnUpload(false);
        DocumentationBuildTrigger quiet = triggerFor(propertiesOf(Map.of(Site.DEFAULT_SITE, configured)));

        quiet.requestBecauseOfUpload(Site.DEFAULT_SITE, "orders");

        verify(requests, never()).requestAll(any(), any(), any(), any(), anyBoolean());
    }

    /**
     * An upload naming a site nobody configured is rejected before it gets here; if one ever did, it must not
     * create a request for a site that will never be found again.
     */
    @Test
    void requestBecauseOfUpload_whenTheSiteIsNotConfigured_thenNothingIsAskedFor() {
        trigger.requestBecauseOfUpload("a-site-nobody-configured", "orders");

        verify(requests, never()).requestAll(any(), any(), any(), any(), anyBoolean());
    }

    /** A system whose name yields no slug documents nothing, so nothing is asked for. */
    @Test
    void requestBecauseOfUpload_whenTheSystemNameYieldsNoSlug_thenNothingIsAskedFor() {
        trigger.requestBecauseOfUpload(Site.DEFAULT_SITE, "***");

        verify(requests, never()).requestAll(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void requestBecauseAnOperatorAsked_thenThePartIsAskedForAndTheRequestIsReported() {
        when(requests.request(eq(SHELL), eq(BuildTrigger.MANUAL), eq(NOW), isNull(), eq(true))).thenReturn(true);
        when(requests.pending()).thenReturn(List.of(new BuildRequest(SHELL, NOW, BuildTrigger.MANUAL, null, true)));

        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(SHELL);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.request().requestedAt()).isEqualTo(NOW);
        assertThat(outcome.request().trigger()).isEqualTo(BuildTrigger.MANUAL);
    }

    /**
     * The collapsing rule holds for this trigger like for every other: the ask joins the request that stands,
     * and the answer says so - with the <i>earlier</i> request's timestamp, which is when the build will happen.
     */
    @Test
    void requestBecauseAnOperatorAsked_whenABuildIsAlreadyPending_thenItJoinsIt() {
        Instant earlier = NOW.minusSeconds(45);
        when(requests.request(eq(SHELL), eq(BuildTrigger.MANUAL), eq(NOW), isNull(), eq(true))).thenReturn(false);
        when(requests.pending()).thenReturn(List.of(new BuildRequest(SHELL, earlier, BuildTrigger.UPLOAD, null, false)));

        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(SHELL);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.request().requestedAt()).isEqualTo(earlier);
        assertThat(outcome.request().trigger()).isEqualTo(BuildTrigger.UPLOAD);
    }

    /**
     * A request pending for another part of the same site is not this part's, and the outcome must not report
     * it: two parts are built independently, and joining the wrong request would say a build is coming when
     * none is.
     */
    @Test
    void requestBecauseAnOperatorAsked_whenAnotherPartHasARequestPending_thenItIsNotReported() {
        when(requests.request(eq(SHELL), eq(BuildTrigger.MANUAL), eq(NOW), isNull(), eq(true))).thenReturn(true);
        when(requests.pending()).thenReturn(List.of(new BuildRequest(ORDERS, NOW, BuildTrigger.UPLOAD, null, false)));

        assertThat(trigger.requestBecauseAnOperatorAsked(SHELL).request()).isNull();
    }

    /**
     * The runner polls, so a request can be claimed between the ask and the read of it. That is a build that has
     * already started, and the outcome says the request is no longer pending rather than inventing one.
     */
    @Test
    void requestBecauseAnOperatorAsked_whenTheRunnerClaimedItInTheMeantime_thenNoStandingRequest() {
        when(requests.request(eq(SHELL), eq(BuildTrigger.MANUAL), eq(NOW), isNull(), eq(true))).thenReturn(true);
        when(requests.pending()).thenReturn(List.of());

        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(SHELL);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.request()).isNull();
    }

    /**
     * A site published only when something is uploaded to it is exactly the site somebody has to be able to
     * publish by hand - so unlike the upload trigger, this one does not ask whether the site wants it.
     */
    @Test
    void requestBecauseAnOperatorAsked_whenTheSiteIsNotPublishedOnUpload_thenItIsStillAskedFor() {
        SiteProperties.Site configured = new SiteProperties.Site();
        configured.setPublishOnUpload(false);
        DocumentationBuildTrigger quiet = triggerFor(propertiesOf(Map.of(Site.DEFAULT_SITE, configured)));

        quiet.requestBecauseAnOperatorAsked(SHELL);

        verify(requests).request(eq(SHELL), eq(BuildTrigger.MANUAL), eq(NOW), any(), eq(true));
    }

    /**
     * The instance that took the trigger looks at once, instead of waiting up to a poll interval to find out
     * what it already knows. One wake-up for the whole burst - see {@link DocumentationBuildPickup}.
     */
    @Test
    void requestBecauseOfUpload_thenThisInstanceIsAskedToLookNow() {
        trigger.requestBecauseOfUpload(Site.DEFAULT_SITE, "ORDERS");

        verify(pickup).whenAskedFor();
    }

    @Test
    void requestBecauseAnOperatorAsked_thenThisInstanceIsAskedToLookNow() {
        trigger.requestBecauseAnOperatorAsked(SHELL);

        verify(pickup).whenAskedFor();
    }

    /**
     * <b>Every part of one ask carries the same publication identifier.</b> It is what makes the wall clock of
     * a full publication measurable at all: its parts are built on several instances, so no single one of them
     * knows when the last of them finished.
     */
    @Test
    void requestEveryPart_thenEveryPartCarriesTheSamePublication() {
        DocumentationBuildTrigger ofALandscape = triggerFor(new SiteProperties(),
                landscapeOf("orders", "shipping"));

        ofALandscape.requestEveryPart(Site.DEFAULT_SITE);

        ArgumentCaptor<Publication> publication = ArgumentCaptor.forClass(Publication.class);
        ArgumentCaptor<List<PartKey>> asked = ArgumentCaptor.captor();
        verify(requests).requestAll(asked.capture(), any(), any(), publication.capture(), anyBoolean());
        assertThat(asked.getValue()).describedAs("every part of the site").hasSize(3);
        assertThat(publication.getValue().requestedAt()).isEqualTo(NOW);
    }

    /** Two asks are two publications, or the wall clock of one would be the elapsed time of both. */
    @Test
    void requestEveryPart_whenAskedTwice_thenTheTwoAsksAreDifferentPublications() {
        trigger.requestEveryPart(Site.DEFAULT_SITE);
        trigger.requestEveryPart(Site.DEFAULT_SITE);

        ArgumentCaptor<Publication> publication = ArgumentCaptor.forClass(Publication.class);
        verify(requests, times(2)).requestAll(any(), any(), any(), publication.capture(), anyBoolean());
        assertThat(publication.getAllValues().get(0).id())
                .isNotEqualTo(publication.getAllValues().get(1).id());
    }

    /** An upload asks for one part, and one part is not a publication. */
    @Test
    void requestBecauseOfUpload_thenTheRequestIsPartOfNoPublication() {
        trigger.requestBecauseOfUpload(Site.DEFAULT_SITE, "ORDERS");

        verify(requests).requestAll(eq(List.of(ORDERS)), eq(BuildTrigger.UPLOAD), eq(NOW), isNull(),
                eq(false));
    }

    /** The import publishes the whole site, so it is a publication like a forced build. */
    @Test
    void requestBecauseTheArchitectureWasImported_thenThePartsCarryOnePublication() {
        DocumentationBuildTrigger ofALandscape = triggerFor(new SiteProperties(),
                landscapeOf("orders", "shipping"));

        ofALandscape.requestBecauseTheArchitectureWasImported("dev");

        ArgumentCaptor<Publication> publication = ArgumentCaptor.forClass(Publication.class);
        verify(requests).requestAll(any(), any(), any(), publication.capture(), anyBoolean());
        assertThat(publication.getValue().id()).isNotNull();
    }

    /**
     * The site an import publishes is left to the import: asking for it here would ask for one landscape
     * twice, an hour apart from itself.
     */
    @Test
    void requestBecauseNothingElsePublishesTheSite_whenAnImportPublishesIt_thenNothingIsAskedFor() {
        DocumentationBuildTrigger ofAnImportedSite = triggerFor(new SiteProperties(),
                landscapeOf("orders", "shipping"));

        assertThat(ofAnImportedSite.requestBecauseNothingElsePublishesTheSite()).isZero();

        verify(requests, never()).requestAll(any(), any(), any(), any(), anyBoolean());
        verify(pickup, never()).whenAskedFor();
    }

    /**
     * A site with no architecture repository has no import to publish it, so the reconcile asks for every part
     * of it. Not forced: what this exists for is a content change nobody asks about - the service version is
     * in the digest - and a part whose content really has not moved is still skipped.
     */
    @Test
    void requestBecauseNothingElsePublishesTheSite_whenNoImportPublishesIt_thenEveryPartIsAskedFor() {
        DocumentationBuildTrigger ofASiteNobodyImportsFor = triggerFor(new SiteProperties());

        assertThat(ofASiteNobodyImportsFor.requestBecauseNothingElsePublishesTheSite()).isEqualTo(1);

        verify(requests).requestAll(eq(List.of(PartKey.shellOf(Site.DEFAULT_SITE))),
                eq(BuildTrigger.SCHEDULE), any(), any(Publication.class), eq(false));
        verify(pickup).whenAskedFor();
        assertThat(metrics.triggered).containsExactly(Site.DEFAULT_SITE + ":SCHEDULE:1");
    }

    /** Fifty parts asked for, and one wake-up: a pass reads what is owed for itself. */
    @Test
    void requestEveryPart_thenThisInstanceIsAskedToLookOnceAndNotOncePerPart() {
        DocumentationBuildTrigger ofALandscape = triggerFor(new SiteProperties(),
                landscapeOf("orders", "shipping", "billing"));

        List<SitePart> asked = ofALandscape.requestEveryPart(Site.DEFAULT_SITE);

        assertThat(asked).hasSize(4);
        verify(pickup, times(1)).whenAskedFor();
    }

    private DocumentationBuildTrigger triggerFor(SiteProperties properties) {
        return triggerFor(properties, new NoArchitectureModel());
    }

    private DocumentationBuildTrigger triggerFor(SiteProperties properties,
                                                 ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource model) {
        DocumentationSites configured = new DocumentationSites(properties);
        return new DocumentationBuildTrigger(requests, configured, new SystemSitePartition(model), model,
                metrics, pickup, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * A landscape of the given systems, in every environment. What the import asks for is every part of the
     * site, and a site has one part per system - so the parts only exist where a model says the systems do.
     */
    private static ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource landscapeOf(String... slugs) {
        return new NoArchitectureModel() {
            @Override
            public boolean isConfiguredFor(String environment) {
                return true;
            }

            @Override
            public List<String> systemSlugsOf(String environment) {
                return List.of(slugs);
            }
        };
    }

    private static SiteProperties propertiesOf(Map<String, SiteProperties.Site> sites) {
        SiteProperties properties = new SiteProperties();
        properties.setSites(new LinkedHashMap<>(sites));
        return properties;
    }
}
