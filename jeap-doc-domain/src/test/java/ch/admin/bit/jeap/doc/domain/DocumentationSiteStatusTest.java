package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * What an operator is shown about a site.
 * <p>
 * The question it exists to answer is <i>why is this site not updating</i>, so what matters here is that the
 * configured intention and what actually happened are both on it: a site that is published on no schedule and
 * gets no uploads is working exactly as configured, and a site whose builds have been failing for a week is not,
 * and neither is visible from what is published.
 */
@ExtendWith(MockitoExtension.class)
class DocumentationSiteStatusTest {

    private static final Instant NOW = Instant.parse("2026-08-28T09:00:00Z");
    private static final String OTHER_SITE = "governance";

    @Mock
    private DocumentationBuildRepository builds;

    @Mock
    private DocumentationBuildRequestRepository requests;

    private DocumentationSiteStatus status;

    @BeforeEach
    void setUp() {
        SiteProperties.Site governance = new SiteProperties.Site();
        governance.setPublicationSchedule(null);
        governance.setPublishOnUpload(false);
        Map<String, SiteProperties.Site> sites = new LinkedHashMap<>();
        sites.put(Site.DEFAULT_SITE, new SiteProperties.Site());
        sites.put(OTHER_SITE, governance);
        SiteProperties properties = new SiteProperties();
        properties.setSites(sites);
        status = new DocumentationSiteStatus(new DocumentationSites(properties), builds, requests);
        lenient().when(requests.pending()).thenReturn(List.of());
        lenient().when(builds.running()).thenReturn(List.of());
        lenient().when(builds.published(anyString())).thenReturn(Optional.empty());
        lenient().when(builds.recent(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    void all_thenEveryConfiguredSiteInTheOrderTheyAreConfigured() {
        assertThat(status.all()).extracting(siteStatus -> siteStatus.site().id())
                .containsExactly(Site.DEFAULT_SITE, OTHER_SITE);
    }

    /**
     * A site nothing has ever built is not an error and must not read like one - it is what an instance looks
     * like on the day it is rolled out.
     */
    @Test
    void of_whenTheSiteHasNeverBeenBuilt_thenNothingPendingRunningOrPublished() {
        SiteStatus siteStatus = status.of(Site.DEFAULT_SITE).orElseThrow();

        assertThat(siteStatus.pending()).isNull();
        assertThat(siteStatus.running()).isEmpty();
        assertThat(siteStatus.published()).isNull();
        assertThat(siteStatus.lastBuild()).isNull();
        assertThat(siteStatus.site().publishOnUpload()).isTrue();
    }

    @Test
    void of_whenTheSiteIsNotConfigured_thenEmpty() {
        assertThat(status.of("a-site-nobody-configured")).isEmpty();
    }

    /**
     * The whole reason {@code lastBuild} is on the status next to {@code published}: the published site is the
     * newest <b>success</b>, so a week of failures behind it is invisible without the last build.
     */
    @Test
    void of_whenTheNewestBuildFailed_thenPublishedAndLastBuildDisagree() {
        DocumentationBuild succeeded = build(41, BuildState.SUCCEEDED, BuildTrigger.SCHEDULE);
        DocumentationBuild failed = build(42, BuildState.FAILED, BuildTrigger.UPLOAD);
        when(builds.published(Site.DEFAULT_SITE)).thenReturn(Optional.of(succeeded));
        when(builds.recent(Site.DEFAULT_SITE, 1)).thenReturn(List.of(failed));

        SiteStatus siteStatus = status.of(Site.DEFAULT_SITE).orElseThrow();

        assertThat(siteStatus.published().id()).isEqualTo(41L);
        assertThat(siteStatus.lastBuild().id()).isEqualTo(42L);
        assertThat(siteStatus.lastBuild().state()).isEqualTo(BuildState.FAILED);
    }

    @Test
    void of_whenABuildIsPending_thenWhenItWasAskedForAndByWhat() {
        when(requests.pending()).thenReturn(List.of(
                new BuildRequest(OTHER_SITE, NOW.minusSeconds(20), BuildTrigger.SCHEDULE),
                new BuildRequest(Site.DEFAULT_SITE, NOW.minusSeconds(45), BuildTrigger.MANUAL)));

        SiteStatus siteStatus = status.of(Site.DEFAULT_SITE).orElseThrow();

        assertThat(siteStatus.pending().requestedAt()).isEqualTo(NOW.minusSeconds(45));
        assertThat(siteStatus.pending().trigger()).isEqualTo(BuildTrigger.MANUAL);
    }

    /**
     * An instance that lost its lock lease and carries on building leaves a second running row for the site
     * until another one abandons it. Showing one of the two would hide exactly the situation an operator who
     * looks here is looking at.
     */
    @Test
    void of_whenTwoBuildsOfTheSiteAreRunning_thenBothAreShown() {
        when(builds.running()).thenReturn(List.of(
                build(51, BuildState.RUNNING, BuildTrigger.MANUAL),
                build(52, BuildState.RUNNING, BuildTrigger.SCHEDULE),
                new DocumentationBuild(53L, OTHER_SITE, BuildTrigger.UPLOAD, BuildState.RUNNING, NOW, null,
                        "doc-service-2", null, 0, 0, 0, null, null)));

        assertThat(status.of(Site.DEFAULT_SITE).orElseThrow().running())
                .extracting(DocumentationBuild::id).containsExactly(51L, 52L);
    }

    @Test
    void all_thenTheRunningBuildsAreSortedOntoTheSiteTheyBelongTo() {
        when(builds.running()).thenReturn(List.of(
                new DocumentationBuild(61L, OTHER_SITE, BuildTrigger.MANUAL, BuildState.RUNNING, NOW, null,
                        "doc-service-1", null, 0, 0, 0, null, null)));

        assertThat(status.all()).filteredOn(siteStatus -> siteStatus.site().id().equals(OTHER_SITE))
                .singleElement()
                .extracting(siteStatus -> siteStatus.running().getFirst().id()).isEqualTo(61L);
        assertThat(status.all()).filteredOn(siteStatus -> siteStatus.site().id().equals(Site.DEFAULT_SITE))
                .singleElement()
                .extracting(SiteStatus::running).asInstanceOf(InstanceOfAssertFactories.LIST).isEmpty();
    }

    private static DocumentationBuild build(long id, BuildState state, BuildTrigger trigger) {
        return new DocumentationBuild(id, Site.DEFAULT_SITE, trigger, state, NOW.minusSeconds(120),
                state == BuildState.RUNNING ? null : NOW.minusSeconds(60), "doc-service-1",
                state == BuildState.SUCCEEDED ? Site.DEFAULT_SITE + "/" + id : null, 0, 0, 0,
                null, null);
    }
}
