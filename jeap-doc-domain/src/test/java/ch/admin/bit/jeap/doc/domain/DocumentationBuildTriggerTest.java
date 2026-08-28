package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one way to ask for a site to be published.
 * <p>
 * Everything that wants a rebuild comes through here, which is what makes the collapsing rule cover all of it -
 * so what this asserts is that nothing gets past it, and that a site which does not want to be published on
 * upload is not.
 */
@ExtendWith(MockitoExtension.class)
class DocumentationBuildTriggerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T09:00:00Z");

    @Mock
    private DocumentationBuildRequestRepository requests;

    private DocumentationBuildTrigger trigger;

    @BeforeEach
    void setUp() {
        trigger = triggerFor(new SiteProperties());
    }

    @Test
    void requestBecauseOfUpload_thenTheSiteIsAskedFor() {
        trigger.requestBecauseOfUpload(Site.DEFAULT_SITE);

        verify(requests).request(eq(Site.DEFAULT_SITE), eq(BuildTrigger.UPLOAD), eq(NOW));
    }

    @Test
    void requestBecauseOfSchedule_thenTheSiteIsAskedFor() {
        trigger.requestBecauseOfSchedule(Site.DEFAULT_SITE);

        verify(requests).request(eq(Site.DEFAULT_SITE), eq(BuildTrigger.SCHEDULE), eq(NOW));
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

        quiet.requestBecauseOfUpload(Site.DEFAULT_SITE);

        verify(requests, never()).request(anyString(), any(), any());
    }

    /**
     * An upload naming a site nobody configured is rejected before it gets here; if one ever did, it must not
     * create a request for a site that will never be found again.
     */
    @Test
    void requestBecauseOfUpload_whenTheSiteIsNotConfigured_thenNothingIsAskedFor() {
        trigger.requestBecauseOfUpload("a-site-nobody-configured");

        verify(requests, never()).request(anyString(), any(), any());
    }

    /**
     * The schedule of a site that is no longer configured is not registered at all, so this path is not guarded
     * the way the upload one is - what it does instead is asked for and dropped by the runner, which says so.
     */
    @Test
    void requestBecauseOfSchedule_whenTheSiteIsNotConfigured_thenItIsStillAskedFor() {
        trigger.requestBecauseOfSchedule("a-site-nobody-configured");

        verify(requests).request(eq("a-site-nobody-configured"), eq(BuildTrigger.SCHEDULE), eq(NOW));
    }

    @Test
    void requestBecauseAnOperatorAsked_thenTheSiteIsAskedForAndTheRequestIsReported() {
        when(requests.request(Site.DEFAULT_SITE, BuildTrigger.MANUAL, NOW)).thenReturn(true);
        when(requests.pending()).thenReturn(List.of(new BuildRequest(Site.DEFAULT_SITE, NOW, BuildTrigger.MANUAL)));

        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(Site.DEFAULT_SITE);

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
        when(requests.request(Site.DEFAULT_SITE, BuildTrigger.MANUAL, NOW)).thenReturn(false);
        when(requests.pending())
                .thenReturn(List.of(new BuildRequest(Site.DEFAULT_SITE, earlier, BuildTrigger.UPLOAD)));

        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(Site.DEFAULT_SITE);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.request().requestedAt()).isEqualTo(earlier);
        assertThat(outcome.request().trigger()).isEqualTo(BuildTrigger.UPLOAD);
    }

    /**
     * The runner polls, so a request can be claimed between the ask and the read of it. That is a build that has
     * already started, and the outcome says the request is no longer pending rather than inventing one.
     */
    @Test
    void requestBecauseAnOperatorAsked_whenTheRunnerClaimedItInTheMeantime_thenNoStandingRequest() {
        when(requests.request(Site.DEFAULT_SITE, BuildTrigger.MANUAL, NOW)).thenReturn(true);
        when(requests.pending()).thenReturn(List.of());

        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(Site.DEFAULT_SITE);

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

        quiet.requestBecauseAnOperatorAsked(Site.DEFAULT_SITE);

        verify(requests).request(eq(Site.DEFAULT_SITE), eq(BuildTrigger.MANUAL), eq(NOW));
    }

    private DocumentationBuildTrigger triggerFor(SiteProperties properties) {
        return new DocumentationBuildTrigger(requests, new DocumentationSites(properties),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SiteProperties propertiesOf(Map<String, SiteProperties.Site> sites) {
        SiteProperties properties = new SiteProperties();
        properties.setSites(new LinkedHashMap<>(sites));
        return properties;
    }
}
