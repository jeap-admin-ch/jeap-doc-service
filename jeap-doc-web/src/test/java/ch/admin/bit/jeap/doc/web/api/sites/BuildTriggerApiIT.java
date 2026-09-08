package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asking for documentation to be published.
 * <p>
 * A site is published as several builds, one per part, so there are two asks: one for the whole site, which
 * walks its parts and asks for the ones whose content has moved, and one for a single part, which asks
 * outright. Neither builds: what they leave behind is the same collapsing request every other trigger leaves,
 * which is what these tests read back. The suite runs with a poll interval longer than itself, so nothing
 * claims the request in between.
 */
class BuildTriggerApiIT extends DocServiceIntegrationTestBase {

    private static final String SITE = "governance";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildRequestRepository requests;

    /**
     * The upload tests leave requests behind and this context is shared, so the state this starts from is made
     * rather than assumed - and it is handed on clean as well.
     */
    @BeforeEach
    @AfterEach
    void withoutAStandingRequest() {
        // Every part of it, and not only its shell: these cases ask for parts by name, and a request left
        // behind by one of them is what the next one would read as its own.
        requests.pending().stream()
                .filter(request -> request.site().equals(SITE))
                .forEach(request -> requests.claim(request.part()));
    }

    /**
     * Asking for a site asks for every part of it, whether its content has moved or not - which is what to use
     * after changing the site template, when the content of a part is the same and nothing else would ask.
     */
    @Test
    void requestBuild_thenAcceptedAndEveryPartIsAskedFor() throws Exception {
        mockMvc.perform(post(SiteApiPaths.BUILDS, SITE).with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.site").value(SITE))
                .andExpect(jsonPath("$.partsRequested").value(1))
                .andExpect(jsonPath("$.picksUpWithinSeconds").isNumber());

        assertThat(standingRequest()).isNotNull()
                .extracting(BuildRequest::trigger).isEqualTo(BuildTrigger.MANUAL);
    }

    /**
     * One part, asked for outright: a part somebody asks for is built whether its content moved or not, so
     * there is nothing to walk and the answer is the request itself.
     */
    @Test
    void requestPartBuild_thenAcceptedAndThatPartIsOwedAManualBuild() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "shell").with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.site").value(SITE))
                .andExpect(jsonPath("$.part").value("shell"))
                .andExpect(jsonPath("$.requested").value(true))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.pendingSince").isNotEmpty());

        assertThat(standingRequest()).isNotNull()
                .extracting(BuildRequest::trigger).isEqualTo(BuildTrigger.MANUAL);
    }

    /** A part this site is not published as is a typo in the request, not something that might appear later. */
    @Test
    void requestPartBuild_whenThePartIsNotOneOfTheSites_thenNotFound() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "system-nobody-documents").with(adminRole()))
                .andExpect(status().isNotFound());
    }

    /**
     * The collapsing rule: however often a part is asked for, it is built once. The second ask says it did not
     * create the request, and answers with the first one's timestamp - which is when the build will happen.
     */
    @Test
    void requestPartBuild_whenABuildIsAlreadyPending_thenItJoinsIt() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "shell").with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested").value(true));

        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "shell").with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested").value(false))
                .andExpect(jsonPath("$.trigger").value("MANUAL"));

        assertThat(requests.pending()).filteredOn(request -> request.site().equals(SITE)).hasSize(1);
    }

    /**
     * What a site is published as, as an operator reads it: its parts, what each carries, and whether one is
     * owed a build - which is true here, because the ask above left a request.
     * <p>
     * <b>Nothing is asserted about what is published.</b> The classes of this module share a database, so
     * whether this site has ever been built depends on what ran before; the state a part is in belongs to the
     * end-to-end test, which controls it.
     */
    @Test
    void parts_thenTheyAreAnsweredWithWhatEachOfThemCarries() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "shell").with(adminRole()))
                .andExpect(status().isAccepted());

        mockMvc.perform(get(SiteApiPaths.PARTS, SITE).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].part").value("shell"))
                .andExpect(jsonPath("$[0].documents").isNotEmpty())
                .andExpect(jsonPath("$[0].environments").isArray())
                .andExpect(jsonPath("$[0].routePrefixes").isArray())
                .andExpect(jsonPath("$[0].owedABuild").value(true));
    }


    /**
     * <b>A part the site still has is never removed.</b> That is the whole safety of the endpoint: it exists
     * for a system that has been decommissioned, and a mistyped part must not take a live system's
     * documentation off the site. The shell is the one part every site always has, so it is the case to hold.
     */
    @Test
    void removePart_whenTheSiteStillHasThePart_thenConflict() throws Exception {
        mockMvc.perform(delete(SiteApiPaths.PART, SITE, "shell").with(adminRole()))
                .andExpect(status().isConflict());

        mockMvc.perform(get(SiteApiPaths.PARTS, SITE).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].part").value("shell"));
    }

    /** A part this site never had and never published: there is nothing to remove and nothing to report. */
    @Test
    void removePart_whenThereIsNothingToRemove_thenNotFound() throws Exception {
        mockMvc.perform(delete(SiteApiPaths.PART, SITE, "system-nobody-documents").with(adminRole()))
                .andExpect(status().isNotFound());
    }

    @Test
    void removePart_whenTheSiteIsNotConfigured_thenNotFound() throws Exception {
        mockMvc.perform(delete(SiteApiPaths.PART, "a-site-nobody-configured", "system-gone").with(adminRole()))
                .andExpect(status().isNotFound());
    }

    /** Removing what a site publishes is not something a read grant may do. */
    @Test
    void removePart_whenTheRoleOnlyReads_thenForbidden() throws Exception {
        mockMvc.perform(delete(SiteApiPaths.PART, SITE, "system-gone").with(readRole()))
                .andExpect(status().isForbidden());
    }

    @Test
    void removePart_whenThereIsNoToken_thenUnauthorized() throws Exception {
        mockMvc.perform(delete(SiteApiPaths.PART, SITE, "system-gone"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A site is configuration, so one that is not there is a typo in the request rather than something that
     * might appear later - and the answer names the sites that are.
     */
    @Test
    void requestBuild_whenTheSiteIsNotConfigured_thenNotFound() throws Exception {
        mockMvc.perform(post(SiteApiPaths.BUILDS, "a-site-nobody-configured").with(adminRole()))
                .andExpect(status().isNotFound());

        assertThat(requests.pending()).extracting(BuildRequest::site)
                .doesNotContain("a-site-nobody-configured");
    }

    @Test
    void requestBuild_whenThereIsNoToken_thenUnauthorized() throws Exception {
        mockMvc.perform(post(SiteApiPaths.BUILDS, SITE))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Reading what the generator has been doing does not include setting it off.
     */
    @Test
    void requestBuild_whenTheRoleOnlyReads_thenForbidden() throws Exception {
        mockMvc.perform(post(SiteApiPaths.BUILDS, SITE)
                        .with(authentication(tokenWithRoles(sitesRole("read")))))
                .andExpect(status().isForbidden());

        assertThat(standingRequest()).isNull();
    }

    /**
     * The upload role is granted per system so that a pipeline can only change its own documentation. A build
     * republishes the documentation of every system on the site, and this is the test that says so.
     */
    @Test
    void requestBuild_whenTheRoleIsAnUploadRole_thenForbidden() throws Exception {
        mockMvc.perform(post(SiteApiPaths.BUILDS, SITE)
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM_NAME, "write")))))
                .andExpect(status().isForbidden());

        assertThat(standingRequest()).isNull();
    }

    /**
     * The per-part endpoint is the same resource with a narrower target, so it is the same three refusals -
     * and it had none of them. A part is one system's documentation, but an operator role and not a system's
     * is what may republish it: an upload role is granted per system to a pipeline, and publishing is not
     * uploading.
     */
    @Test
    void requestPartBuild_whenThereIsNoToken_thenUnauthorized() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "shell"))
                .andExpect(status().isUnauthorized());

        assertThat(standingRequest()).isNull();
    }

    @Test
    void requestPartBuild_whenTheRoleOnlyReads_thenForbidden() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "shell")
                        .with(authentication(tokenWithRoles(sitesRole("read")))))
                .andExpect(status().isForbidden());

        assertThat(standingRequest()).isNull();
    }

    @Test
    void requestPartBuild_whenTheRoleIsAnUploadRole_thenForbidden() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, SITE, "system-" + SYSTEM_NAME.toLowerCase())
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM_NAME, "write")))))
                .andExpect(status().isForbidden());

        assertThat(standingRequest()).isNull();
    }

    @Test
    void requestPartBuild_whenTheSiteIsNotConfigured_thenNotFound() throws Exception {
        mockMvc.perform(post(SiteApiPaths.PART_BUILDS, "a-site-nobody-configured", "shell").with(adminRole()))
                .andExpect(status().isNotFound());
    }

    private BuildRequest standingRequest() {
        return requests.pending().stream()
                .filter(request -> request.site().equals(SITE))
                .findFirst()
                .orElse(null);
    }

    private static RequestPostProcessor adminRole() {
        return authentication(tokenWithRoles(sitesRole("admin")));
    }

    private static RequestPostProcessor readRole() {
        return authentication(tokenWithRoles(sitesRole("read")));
    }
}
