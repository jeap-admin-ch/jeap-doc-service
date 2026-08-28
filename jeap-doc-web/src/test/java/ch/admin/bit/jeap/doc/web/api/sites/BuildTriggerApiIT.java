package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asking for a site to be published.
 * <p>
 * The endpoint asks and does not build: what it leaves behind is the same collapsing request every other trigger
 * leaves, which is what these tests read back. The suite runs with a poll interval longer than itself, so
 * nothing claims the request in between.
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
        requests.claim(SITE);
    }

    @Test
    void requestBuild_thenAcceptedAndTheSiteIsOwedAManualBuild() throws Exception {
        mockMvc.perform(post(SiteApiPaths.BUILDS, SITE).with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.site").value(SITE))
                .andExpect(jsonPath("$.requested").value(true))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.pendingSince").isNotEmpty())
                .andExpect(jsonPath("$.picksUpWithinSeconds").isNumber());

        assertThat(standingRequest()).isNotNull()
                .extracting(BuildRequest::trigger).isEqualTo(BuildTrigger.MANUAL);
    }

    /**
     * The collapsing rule: however often it is asked for, the site is built once. The second ask says it did not
     * create the request, and answers with the first one's timestamp - which is when the build will happen.
     */
    @Test
    void requestBuild_whenABuildIsAlreadyPending_thenItJoinsIt() throws Exception {
        mockMvc.perform(post(SiteApiPaths.BUILDS, SITE).with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested").value(true));

        mockMvc.perform(post(SiteApiPaths.BUILDS, SITE).with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested").value(false))
                .andExpect(jsonPath("$.trigger").value("MANUAL"));

        assertThat(requests.pending()).filteredOn(request -> request.site().equals(SITE)).hasSize(1);
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

    private BuildRequest standingRequest() {
        return requests.pending().stream()
                .filter(request -> request.site().equals(SITE))
                .findFirst()
                .orElse(null);
    }

    private static RequestPostProcessor adminRole() {
        return authentication(tokenWithRoles(sitesRole("admin")));
    }
}
