package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
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
        DocumentationBuild older = finished(BuildTrigger.IMPORT);
        DocumentationBuild newer = finished(BuildTrigger.MANUAL);

        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(newer.id()))
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[1].id").value(older.id()));
    }

    @Test
    void builds_whenALimitIsGiven_thenAtMostThatMany() throws Exception {
        finished(BuildTrigger.IMPORT);
        finished(BuildTrigger.IMPORT);

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
            finished(BuildTrigger.IMPORT);
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
        DocumentationBuild build = builds.start(PartKey.shellOf(SITE), BuildTrigger.MANUAL, INSTANCE, now, null);
        builds.failed(build.id(), "Docusaurus exited with 1", now.plusSeconds(11));

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

    /**
     * The history of <b>one part</b>, which had no test at all - and it is the one an operator reads: the
     * site-wide history of a fifty-two-part site is fifty-two parts interleaved, so a part's own rows are what
     * answer "what has been happening to this system's documentation".
     */
    @Test
    void partBuilds_thenTheBuildsOfThatPartAloneNewestFirst() throws Exception {
        DocumentationBuild older = finished(BuildTrigger.IMPORT);
        DocumentationBuild newer = finished(BuildTrigger.MANUAL);
        // A row of another part of the same site. This site reads no architecture model, so the endpoint has
        // no such part to answer for - but the site-wide history has the row, and this one must not.
        DocumentationBuild ofAnotherPart = finishedPart("system-orders", BuildTrigger.UPLOAD);

        mockMvc.perform(get(SiteApiPaths.PART_BUILDS, SITE, "shell").with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(newer.id()))
                .andExpect(jsonPath("$[1].id").value(older.id()))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(ofAnotherPart.id().intValue()))));

        // And the site-wide history does have it, so the part endpoint is filtering rather than the row
        // being absent.
        mockMvc.perform(get(SiteApiPaths.BUILDS, SITE).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(ofAnotherPart.id().intValue())));
    }

    /**
     * The same rule as the site-wide history: a limit outside the range is the range, not an error. Both ends
     * asserted with more history than the cap, because a cap that is never reached is a cap nothing proves.
     */
    @Test
    void partBuilds_whenTheLimitIsOutsideTheRange_thenItIsBroughtIntoIt() throws Exception {
        for (int build = 0; build <= SiteAdminController.MAX_HISTORY_LIMIT; build++) {
            finished(BuildTrigger.IMPORT);
        }

        mockMvc.perform(get(SiteApiPaths.PART_BUILDS, SITE, "shell").param("limit", "100000")
                        .with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(SiteAdminController.MAX_HISTORY_LIMIT)));
        mockMvc.perform(get(SiteApiPaths.PART_BUILDS, SITE, "shell").param("limit", "0")
                        .with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(1)));
    }

    /**
     * A part this site is not published as is a 404 rather than an empty list - including a well-formed
     * {@code system-<slug>} whose system no environment's model knows, which is a part nothing builds.
     */
    @Test
    void partBuilds_whenThePartIsNotOneOfTheSites_thenNotFound() throws Exception {
        mockMvc.perform(get(SiteApiPaths.PART_BUILDS, SITE, "not-a-part-of-anything").with(readRole()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(SiteApiPaths.PART_BUILDS, SITE, "system-nobody-documents").with(readRole()))
                .andExpect(status().isNotFound());
    }

    @Test
    void partBuilds_whenTheRoleOnlyAdministers_thenForbidden() throws Exception {
        mockMvc.perform(get(SiteApiPaths.PART_BUILDS, SITE, "shell")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isForbidden());
    }

    private DocumentationBuild finishedPart(String part, BuildTrigger trigger) {
        Instant now = Instant.now();
        DocumentationBuild build = builds.start(PartKey.of(SITE, part), trigger, INSTANCE, now, null);
        builds.succeeded(build.id(), SITE + "/" + build.id(), 3, 512, 100, "digest", now.plusSeconds(5));
        return build;
    }

    private DocumentationBuild finished(BuildTrigger trigger) {
        Instant now = Instant.now();
        DocumentationBuild build = builds.start(PartKey.shellOf(SITE), trigger, INSTANCE, now, null);
        builds.succeeded(build.id(), SITE + "/" + build.id(), 3, 512, 100, "digest",
                now.plusSeconds(5));
        return build;
    }

    private static RequestPostProcessor readRole() {
        return authentication(tokenWithRoles(sitesRole("read")));
    }
}
