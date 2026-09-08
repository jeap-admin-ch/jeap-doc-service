package ch.admin.bit.jeap.doc.web.api.architecture;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asking for the architecture repository to be imported.
 * <p>
 * <b>Why the endpoint exists.</b> A build reads what the import stored and calls the architecture repository
 * not at all, so a correction made there is invisible to the documentation until the next scheduled import -
 * and forcing a publication would publish the old model again.
 * <p>
 * <b>Asking is not importing</b>, so these cases assert what the API answers and not what an import did: the
 * ask goes onto the one import thread and the request is back within a millisecond. What an import does is
 * {@code ArchitectureImportJobTest}'s and {@code DocumentationGenerationIT}'s business.
 * <p>
 * The environment is <b>{@code abn}</b>, which no other test class of this module configures. The import lock
 * is per environment and kind and every context here shares one database, so two classes importing the same
 * environment would take each other's lock - and the arch repo of this one answers 503 to everything, so the
 * import that an ask really starts fails at once instead of running for minutes against nothing.
 */
class ArchitectureImportApiIT extends DocServiceIntegrationTestBase {

    private static final String ENVIRONMENT = "abn";

    private static final WireMockServer ARCH_REPO = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void archRepoProperties(DynamicPropertyRegistry registry) {
        ARCH_REPO.start();
        ARCH_REPO.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503)));
        registry.add("jeap.doc.archrepo.environments." + ENVIRONMENT + ".url", ARCH_REPO::baseUrl);
        registry.add("jeap.doc.archrepo.environments." + ENVIRONMENT + ".client-registration",
                () -> "archrepo");
        // The arch repo client is authorized by the authorization server of its stage, so a context that
        // configures an environment has to configure the registration it names or no bean can be built.
        registry.add("spring.security.oauth2.client.registration.archrepo.client-id", () -> "jme-doc-service");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-secret", () -> "secret");
        registry.add("spring.security.oauth2.client.registration.archrepo.authorization-grant-type",
                () -> "client_credentials");
        registry.add("spring.security.oauth2.client.registration.archrepo.provider", () -> "archrepo");
        registry.add("spring.security.oauth2.client.provider.archrepo.token-uri",
                () -> "http://localhost/auth/realms/test/protocol/openid-connect/token");
        // Nothing is imported on a schedule or at startup here: what an import does is asserted elsewhere,
        // and a run of its own starting underneath these cases is a state they would have to know about.
        registry.add("jeap.doc.archrepo.import.cron", () -> "");
        registry.add("jeap.doc.archrepo.import.on-startup", () -> "false");
    }

    @AfterAll
    static void stopTheArchRepo() {
        ARCH_REPO.stop();
    }

    /**
     * The shape of the state index: one entry per configured environment, with an entry per import kind and
     * the model first, because it is what decides which systems exist and therefore which artifacts are
     * orphans.
     * <p>
     * <b>Every kind, counted against the enum.</b> The endpoint reports
     * {@code ArchitectureImportKind.values()}, so a kind added or dropped changes what a caller reads - and a
     * case asserting three entries by index would not notice.
     * <p>
     * <b>This environment's entry, found by its id rather than by position.</b> The index carries one entry
     * per configured environment, and what else is configured is the context's business, not this case's.
     * <p>
     * Only the fields an import cannot change are asserted. An ask made by another case runs on a thread of
     * its own, so what the last outcome is at any moment is not this test's to know.
     */
    @Test
    void environments_thenEachConfiguredOneIsReportedWithEveryKindModelFirst() throws Exception {
        String own = "$[?(@.environment=='" + ENVIRONMENT + "')]";

        mockMvc.perform(get(ArchitectureApiPaths.ENVIRONMENTS).with(readRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(own).exists())
                .andExpect(jsonPath(own + ".sourceUrl").value(hasItem(ARCH_REPO.baseUrl())))
                .andExpect(jsonPath(own + ".imports[*].kind")
                        .value(hasSize(ArchitectureImportKind.values().length)))
                .andExpect(jsonPath(own + ".imports[0].kind").value(hasItem("MODEL")))
                .andExpect(jsonPath(own + ".imports[1].kind").value(hasItem("OPENAPI_SPEC")))
                .andExpect(jsonPath(own + ".imports[2].kind").value(hasItem("DATABASE_SCHEMA")))
                .andExpect(jsonPath(own + ".imports[3].kind").value(hasItem("MESSAGE_SCHEMA")));
    }

    /**
     * The answer names the environment, whether this ask put the import on the queue or joined one that was
     * already there - a second ask for an environment already queued is not queued twice, and which of the
     * two happened depends on whether the import thread has reached the earlier one.
     */
    @Test
    void requestImport_thenAcceptedAndTheEnvironmentIsNamed() throws Exception {
        String answer = mockMvc.perform(post(ArchitectureApiPaths.ENVIRONMENT_IMPORTS, ENVIRONMENT)
                        .with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.durable").value(false))
                .andExpect(jsonPath("$.refused").value(empty()))
                .andReturn().getResponse().getContentAsString();

        assertThat(answer).contains(ENVIRONMENT);
    }

    @Test
    void requestEveryImport_thenAcceptedAndEveryConfiguredEnvironmentIsNamed() throws Exception {
        String answer = mockMvc.perform(post(ArchitectureApiPaths.IMPORTS).with(adminRole()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.refused").value(empty()))
                .andReturn().getResponse().getContentAsString();

        assertThat(answer).contains(ENVIRONMENT);
    }

    /**
     * An environment nobody reads is a typo, and the typo is the likely reason for asking twice. Importing it
     * would take a lock, log a failure and puzzle whoever asked.
     */
    @Test
    void requestImport_whenTheEnvironmentIsNotConfigured_thenNotFound() throws Exception {
        mockMvc.perform(post(ArchitectureApiPaths.ENVIRONMENT_IMPORTS, "an-environment-nobody-reads")
                        .with(adminRole()))
                .andExpect(status().isNotFound());
    }

    /** Asking for an import is administering the documentation, so reading is not enough. */
    @Test
    void requestImport_whenTheCallerMayOnlyRead_thenForbidden() throws Exception {
        mockMvc.perform(post(ArchitectureApiPaths.ENVIRONMENT_IMPORTS, ENVIRONMENT).with(readRole()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ArchitectureApiPaths.IMPORTS).with(readRole()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theEndpoints_withoutAToken_areRefused() throws Exception {
        mockMvc.perform(get(ArchitectureApiPaths.ENVIRONMENTS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ArchitectureApiPaths.IMPORTS)).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor adminRole() {
        return authentication(tokenWithRoles(sitesRole("admin")));
    }

    private static RequestPostProcessor readRole() {
        return authentication(tokenWithRoles(sitesRole("read")));
    }
}
