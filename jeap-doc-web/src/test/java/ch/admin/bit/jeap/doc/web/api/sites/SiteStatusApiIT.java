package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an operator is shown about the sites.
 * <p>
 * The question behind this endpoint is <i>why is this site not updating</i>, so the tests check that the
 * configured intention is on it next to what actually happened - a site with no schedule that nothing uploads to
 * is behaving exactly as configured, and nothing else on the status would say so.
 */
class SiteStatusApiIT extends DocServiceIntegrationTestBase {

    private static final String SITE = "governance";
    private static final String INSTANCE = "site-status-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildRepository builds;

    @Test
    void sites_thenEveryConfiguredSiteWithWhatItIsConfiguredToDo() throws Exception {
        mockMvc.perform(get(SiteApiPaths.SITES).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].site").value(hasItems(Site.DEFAULT_SITE, SITE)))
                .andExpect(jsonPath("$[?(@.site == 'default')].title").value(hasItems("Documentation")))
                .andExpect(jsonPath("$[?(@.site == 'default')].publishOnUpload").value(hasItems(true)))
                .andExpect(jsonPath("$[?(@.site == 'default')].publicationSchedule").isNotEmpty())
                .andExpect(jsonPath("$[?(@.site == 'default')].environments[*]").value(hasItems("dev", "prod")));
    }

    /**
     * The published site is the newest <b>success</b>, so a run of failures behind it is invisible without the
     * last build - which is the whole reason both are on the status.
     */
    @Test
    void site_whenTheNewestBuildFailed_thenPublishedAndLastBuildDisagree() throws Exception {
        Instant now = Instant.now();
        DocumentationBuild succeeded = builds.start(SITE, BuildTrigger.SCHEDULE, INSTANCE, now);
        builds.succeeded(succeeded.id(), SITE + "/" + succeeded.id(), 12, 4096, 3000, null, now.plusSeconds(30));
        DocumentationBuild failed = builds.start(SITE, BuildTrigger.MANUAL, INSTANCE, now.plusSeconds(60));
        builds.failed(failed.id(), "npm exited with 1", null, now.plusSeconds(70));

        mockMvc.perform(get(SiteApiPaths.SITE, SITE).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.site").value(SITE))
                .andExpect(jsonPath("$.published.id").value(succeeded.id()))
                .andExpect(jsonPath("$.published.pageCount").value(12))
                .andExpect(jsonPath("$.published.objectPrefix").value(SITE + "/" + succeeded.id()))
                .andExpect(jsonPath("$.lastBuild.id").value(failed.id()))
                .andExpect(jsonPath("$.lastBuild.state").value("FAILED"))
                .andExpect(jsonPath("$.lastBuild.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.lastBuild.failureReason").value("npm exited with 1"));
    }

    /**
     * A build that is running is the answer to <i>is something happening right now</i>, which is the other half
     * of why this endpoint exists. It is finished again at the end, because the context is shared.
     */
    @Test
    void site_whenABuildIsRunning_thenItIsOnTheStatusWithTheInstanceRunningIt() throws Exception {
        Instant now = Instant.now();
        DocumentationBuild running = builds.start(SITE, BuildTrigger.MANUAL, INSTANCE, now);
        try {
            mockMvc.perform(get(SiteApiPaths.SITE, SITE).with(readRole()))
                    .andExpect(status().isOk())
                    // The newest first, and this test just started it: no other test of this suite leaves a
                    // build of this site running.
                    .andExpect(jsonPath("$.running[0].id").value(running.id()))
                    .andExpect(jsonPath("$.running[0].instance").value(INSTANCE))
                    .andExpect(jsonPath("$.running[0].state").value("RUNNING"))
                    .andExpect(jsonPath("$.running[0].finishedAt").doesNotExist());
        } finally {
            builds.failed(running.id(), "ended by the test", null, now.plusSeconds(1));
        }
    }

    @Test
    void site_whenTheSiteIsNotConfigured_thenNotFound() throws Exception {
        mockMvc.perform(get(SiteApiPaths.SITE, "a-site-nobody-configured").with(readRole()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sites_whenThereIsNoToken_thenUnauthorized() throws Exception {
        mockMvc.perform(get(SiteApiPaths.SITES))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Administering a site is asking for it to be built; reading what it has been doing is its own operation and
     * its own role, so the one does not imply the other.
     */
    @Test
    void sites_whenTheRoleOnlyAdministers_thenForbidden() throws Exception {
        mockMvc.perform(get(SiteApiPaths.SITES).with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isForbidden());
    }

    /**
     * The upload role is granted per system. A site carries the documentation of every system on it, so a grant
     * for one system is not a way to read what the whole site has been doing.
     */
    @Test
    void sites_whenTheRoleIsAnUploadRole_thenForbidden() throws Exception {
        mockMvc.perform(get(SiteApiPaths.SITES)
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM_NAME, "write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void sites_whenTheRoleIsTheDocsReadRole_thenForbidden() throws Exception {
        mockMvc.perform(get(SiteApiPaths.SITES).with(authentication(tokenWithRoles(docsRole("read")))))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor readRole() {
        return authentication(tokenWithRoles(sitesRole("read")));
    }
}
