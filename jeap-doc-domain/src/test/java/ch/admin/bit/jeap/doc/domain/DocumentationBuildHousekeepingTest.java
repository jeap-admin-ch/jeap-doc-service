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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentationBuildHousekeepingTest {

    private static final Instant NOW = Instant.parse("2026-08-25T02:45:00Z");

    @Mock
    private DocumentationBuildRepository builds;

    @Test
    void removeOldBuilds_thenTheRecordsOlderThanTheRetentionGo() {
        DocumentationBuildHousekeeping housekeeping = housekeeping(new SiteProperties());
        when(builds.published(Site.DEFAULT_SITE)).thenReturn(Optional.empty());
        when(builds.deleteFinishedBefore(any(), anySet())).thenReturn(3);

        housekeeping.removeOldBuilds();

        ArgumentCaptor<Instant> finishedBefore = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(builds).deleteFinishedBefore(finishedBefore.capture(), anySet());
        assertThat(finishedBefore.getValue()).isEqualTo(NOW.minus(new BuildProperties().getHistoryRetention()));
    }

    /**
     * The one rule this class exists for: the newest successful build of a site is not only a record, it is the
     * publication. Losing it would leave a rarely-built site answering that it has never been generated.
     */
    @Test
    void removeOldBuilds_thenThePublishedBuildOfEverySiteIsKept() {
        SiteProperties properties = new SiteProperties();
        Map<String, SiteProperties.Site> sites = new LinkedHashMap<>();
        sites.put(Site.DEFAULT_SITE, new SiteProperties.Site());
        sites.put("governance", new SiteProperties.Site());
        properties.setSites(sites);
        when(builds.published(Site.DEFAULT_SITE)).thenReturn(Optional.of(build(11L)));
        when(builds.published("governance")).thenReturn(Optional.of(build(22L)));

        housekeeping(properties).removeOldBuilds();

        ArgumentCaptor<Set<Long>> keep = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(builds).deleteFinishedBefore(any(), keep.capture());
        assertThat(keep.getValue()).containsExactlyInAnyOrder(11L, 22L);
    }

    @Test
    void removeOldBuilds_whenASiteHasNeverBeenPublished_thenItContributesNothingToKeep() {
        when(builds.published(Site.DEFAULT_SITE)).thenReturn(Optional.empty());

        housekeeping(new SiteProperties()).removeOldBuilds();

        ArgumentCaptor<Set<Long>> keep = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(builds).deleteFinishedBefore(any(), keep.capture());
        assertThat(keep.getValue()).isEmpty();
    }

    private DocumentationBuildHousekeeping housekeeping(SiteProperties properties) {
        return new DocumentationBuildHousekeeping(builds, new DocumentationSites(properties),
                new BuildProperties(), Clock.fixed(NOW, ZoneOffset.UTC), new DirectExclusiveWork());
    }

    private static DocumentationBuild build(long id) {
        return new DocumentationBuild(id, Site.DEFAULT_SITE, BuildTrigger.SCHEDULE, BuildState.SUCCEEDED,
                NOW, NOW, "test", "default/" + id, 1, 1, 1, null);
    }
}
