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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The record of what the generator has run, as an operator reads it back.
 */
class BuildHistoryApiIT extends DocServiceIntegrationTestBase {

    private static final String SITE = "governance";
    private static final String INSTANCE = "build-history-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildRepository builds;

    @Test
    void builds_thenNewestFirst() throws Exception {
        DocumentationBuild older = finished(BuildTrigger.SCHEDULE);
        DocumentationBuild newer = finished(BuildTrigger.MANUAL);

        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(newer.id()))
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[1].id").value(older.id()));
    }

    @Test
    void builds_whenALimitIsGiven_thenAtMostThatMany() throws Exception {
        finished(BuildTrigger.SCHEDULE);
        finished(BuildTrigger.SCHEDULE);

        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE).param("limit", "1").with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(1)));
    }

    /**
     * An operator asking for everything gets the newest hundred rather than an error: the useful answer to a
     * limit outside the range is the range, and refusing it would only cost a second request. Both ends are
     * asserted with more history than the cap, because a cap that is never reached is a cap nothing proves.
     */
    @Test
    void builds_whenTheLimitIsOutsideTheRange_thenItIsBroughtIntoIt() throws Exception {
        for (int build = 0; build <= SiteAdminController.MAX_HISTORY_LIMIT; build++) {
            finished(BuildTrigger.SCHEDULE);
        }

        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE).param("limit", "100000").with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(SiteAdminController.MAX_HISTORY_LIMIT)));
        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE).param("limit", "0").with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(1)));
    }

    @Test
    void build_thenTheOneBuildWithWhatWentWrongWithIt() throws Exception {
        Instant now = Instant.now();
        DocumentationBuild build = builds.start(SITE, BuildTrigger.MANUAL, INSTANCE, now);
        builds.failed(build.id(), "Docusaurus exited with 1", null, now.plusSeconds(11));

        mockMvc.perform(get(SiteApiPaths.BUILDS + "/{buildId}", SITE, build.id()).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(build.id()))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.instance").value(INSTANCE))
                .andExpect(jsonPath("$.durationMillis").value(11_000))
                .andExpect(jsonPath("$.failureReason").value("Docusaurus exited with 1"));
    }

    /**
     * The identifiers come from one sequence shared by every site, so without the site in the query the URL of
     * one site would answer with a build of another.
     */
    @Test
    void build_whenTheBuildBelongsToAnotherSite_thenNotFound() throws Exception {
        DocumentationBuild build = finished(BuildTrigger.MANUAL);

        mockMvc.perform(get(SiteApiPaths.BUILDS + "/{buildId}", Site.DEFAULT_SITE, build.id()).with(readRole()))
                .andExpect(status().isNotFound());
    }

    @Test
    void builds_whenTheSiteIsNotConfigured_thenNotFound() throws Exception {
        mockMvc.perform(get(SiteApiPaths.BUILDS, "a-site-nobody-configured").with(readRole()))
                .andExpect(status().isNotFound());
    }

    @Test
    void builds_whenThereIsNoToken_thenUnauthorized() throws Exception {
        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The upload role is granted per system, and the history of a site is every system's on it.
     */
    @Test
    void builds_whenTheRoleIsAnUploadRole_thenForbidden() throws Exception {
        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE)
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM_NAME, "write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void builds_whenTheRoleOnlyAdministers_thenForbidden() throws Exception {
        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE)
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isForbidden());
    }

    private DocumentationBuild finished(BuildTrigger trigger) {
        Instant now = Instant.now();
        DocumentationBuild build = builds.start(SITE, trigger, INSTANCE, now);
        builds.succeeded(build.id(), SITE + "/" + build.id(), 3, 512, 100, null, now.plusSeconds(5));
        return build;
    }

    private static RequestPostProcessor readRole() {
        return authentication(tokenWithRoles(sitesRole("read")));
    }
}
