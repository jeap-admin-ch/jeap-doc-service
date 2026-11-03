package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.port.GovernanceIndexFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The governance content source: shell only, and refused where no Governance Service is configured for it.
 */
class GovernanceContentTest {

    @Test
    void id_thenGovernance() {
        assertThat(new GovernanceContent(upstream(true)).id()).isEqualTo(GovernanceContent.SOURCE_ID);
    }

    @Test
    void publishedByTheGovernanceImport_thenTrue() {
        assertThat(new GovernanceContent(upstream(true)).publishedByTheGovernanceImport()).isTrue();
    }

    @Test
    void takesSiteUploads_thenFalse() {
        assertThat(new GovernanceContent(upstream(true)).takesSiteUploads()).isFalse();
    }

    @Test
    void check_whenNoGovernanceServiceIsConfigured_thenStartupIsRefused() {
        GovernanceContent governance = new GovernanceContent(upstream(false));

        assertThatThrownBy(() -> governance.check(siteOf("governance")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("governance")
                .hasMessageContaining("no Governance Service is configured");
    }

    /** The Governance Service is read once per instance, not once per stage - there is no tree to show twice. */
    @Test
    void check_whenTheSiteHasEnvironments_thenStartupIsRefused() {
        GovernanceContent governance = new GovernanceContent(upstream(true));
        SiteEnvironment prod = new SiteEnvironment("prod", "PROD", "Production", 1, true, true);
        Site withEnvironments = new Site("governance", "Governance", null, null, null, null, List.of(prod),
                false, false, 1, null, GovernanceContent.SOURCE_ID);

        assertThatThrownBy(() -> governance.check(withEnvironments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("governance")
                .hasMessageContaining("per-environment: false");
    }

    @Test
    void check_whenAGovernanceServiceIsConfigured_thenTheStartupIsNotRefused() {
        GovernanceContent governance = new GovernanceContent(upstream(true));

        governance.check(siteOf("governance"));
    }

    private static GovernanceUpstream upstream(boolean configured) {
        return new GovernanceUpstream() {

            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public Optional<GovernanceIndexFetch> systemsIndex(String knownIndexEtag) {
                throw new UnsupportedOperationException("this test checks the startup guard, nothing is fetched");
            }

            @Override
            public Optional<GovernanceIndexFetch> rulesIndex(String knownIndexEtag) {
                throw new UnsupportedOperationException("this test checks the startup guard, nothing is fetched");
            }

            @Override
            public GovernanceResourceFetch system(String systemName, String knownEtag) {
                throw new UnsupportedOperationException("this test checks the startup guard, nothing is fetched");
            }

            @Override
            public GovernanceResourceFetch rule(String ruleId, String knownEtag) {
                throw new UnsupportedOperationException("this test checks the startup guard, nothing is fetched");
            }
        };
    }

    private static Site siteOf(String id) {
        return new Site(id, "Governance", null, null, null, null, List.of(), false, false, 1, null,
                GovernanceContent.SOURCE_ID);
    }
}
