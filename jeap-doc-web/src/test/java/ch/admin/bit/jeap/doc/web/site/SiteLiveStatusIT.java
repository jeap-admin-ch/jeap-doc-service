package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.DocumentationLiveStatus;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.doc.web.api.architecture.ArchitectureApiPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The live status of a documentation site, which the page describing the documentation fetches.
 * <p>
 * <b>Why it is not on the page.</b> Whether the architecture repository is still being read, and when the
 * import fires next, change without the documentation changing - and a part whose content has not moved is not
 * generated again, so a page carrying them would freeze into a claim nobody can correct. Taking them out of the
 * content is what lets the part that carries whole environment trees be skipped; answering them here is what
 * keeps them true.
 * <p>
 * The site is <b>{@code livestatus}</b> and its environments are its own, because every context of this module
 * shares one database: the import state row this class writes would otherwise be read by a class asserting
 * something about {@code dev} or {@code prod}.
 */
class SiteLiveStatusIT extends DocServiceIntegrationTestBase {

    private static final String SITE = "livestatus";
    private static final String ENVIRONMENT = "lsmain";
    private static final String ENVIRONMENT_WITHOUT_A_MODEL = "lslatest";

    /** Once a year, so that nothing this class configures can run an import while it is asserting. */
    private static final String IMPORT_CRON = "0 0 4 1 1 *";

    /**
     * What an import state row carries that this resource may not repeat. The page and this file are served to
     * anyone who can reach the service; a failure reason quotes the upstream's host and its paths.
     */
    private static final String FAILURE_REASON =
            "The architecture repository https://archrepo.internal.admin.ch/docs-api answered 503";

    /** Seconds, because that is the resolution the timestamp is shown at. */
    private static final Instant LAST_READ = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            .minus(Duration.ofMinutes(15));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArchitectureImportRepository imports;

    @DynamicPropertySource
    static void siteProperties(DynamicPropertyRegistry registry) {
        registry.add("jeap.doc.sites." + SITE + ".title", () -> "Live");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].id", () -> ENVIRONMENT);
        registry.add("jeap.doc.sites." + SITE + ".environments[0].order", () -> 1);
        registry.add("jeap.doc.sites." + SITE + ".environments[0].main", () -> true);
        registry.add("jeap.doc.sites." + SITE + ".environments[1].id", () -> ENVIRONMENT_WITHOUT_A_MODEL);
        registry.add("jeap.doc.sites." + SITE + ".environments[1].order", () -> 2);
        registry.add("jeap.doc.sites." + SITE + ".environments[1].latest", () -> true);
        // One of the two environments reads an architecture repository and the other does not, which is what
        // makes the difference between "nothing to report" and "not read yet" visible in one answer.
        registry.add("jeap.doc.archrepo.environments." + ENVIRONMENT + ".url",
                () -> "http://localhost:1/docs-api");
        registry.add("jeap.doc.archrepo.environments." + ENVIRONMENT + ".client-registration",
                () -> "archrepo");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-id", () -> "doc-service");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-secret", () -> "secret");
        registry.add("spring.security.oauth2.client.registration.archrepo.authorization-grant-type",
                () -> "client_credentials");
        registry.add("spring.security.oauth2.client.registration.archrepo.provider", () -> "archrepo");
        registry.add("spring.security.oauth2.client.provider.archrepo.token-uri",
                () -> "http://localhost/auth/realms/test/protocol/openid-connect/token");
        registry.add("jeap.doc.archrepo.import.cron", () -> IMPORT_CRON);
        registry.add("jeap.doc.archrepo.import.on-startup", () -> "false");
    }

    @BeforeEach
    void recordAnImportThatFailedAfterASuccessfulOne() {
        imports.save(new ArchitectureImportState(ENVIRONMENT, ArchitectureImportKind.MODEL, "a-content-hash",
                null, false, 7, Instant.now(), LAST_READ, ImportOutcome.FAILED, FAILURE_REASON));
    }

    /**
     * One fetch fills the whole table: every environment of the site, and the schedule its rows print. A
     * resource per environment would mean one request per row, and a row with no answer.
     */
    @Test
    void get_thenEveryEnvironmentOfTheSiteAndTheScheduleThePageTabulates() throws Exception {
        mockMvc.perform(get(path(SITE)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.site").value(SITE))
                .andExpect(jsonPath("$.at").exists())
                .andExpect(jsonPath("$.environments[0].id").value(ENVIRONMENT))
                .andExpect(jsonPath("$.environments[0].modelConfigured").value(true))
                .andExpect(jsonPath("$.environments[0].lastReadAt").value(LAST_READ.toString()))
                .andExpect(jsonPath("$.environments[0].lastOutcome").value("FAILED"))
                .andExpect(jsonPath("$.environments[0].behind").value(false))
                // The outcome belongs to the latest run and the timestamp to the last successful one, so the
                // two are named apart: joining them would say the read that worked did not.
                .andExpect(jsonPath("$.environments[0].lastRead")
                        .value(DisplayTime.of(LAST_READ) + "; the last run did not read it"))
                // An environment with no architecture repository has nothing to report, so the cell the page
                // left for it stays as it was written.
                .andExpect(jsonPath("$.environments[1].id").value(ENVIRONMENT_WITHOUT_A_MODEL))
                .andExpect(jsonPath("$.environments[1].modelConfigured").value(false))
                .andExpect(jsonPath("$.environments[1].lastRead").value(""))
                .andExpect(jsonPath("$.schedules[0].cron").value(IMPORT_CRON))
                .andExpect(jsonPath("$.schedules[0].next").exists());
    }

    /**
     * <b>The whole point of it.</b> A cached answer would be the frozen page again with an extra step: what
     * this carries changes while the documentation does not.
     */
    @Test
    void get_thenItIsNeverCached() throws Exception {
        mockMvc.perform(get(path(SITE)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    /**
     * <b>It is a path of the site, so it is open exactly as the site is</b> - the statements it carries are the
     * ones the page used to print, and the page needs no token. The administration API that reads the same rows
     * does. Both halves in one case, because it is one decision: putting this below {@code /api} would have put
     * a bearer token in front of a table on a public page, and opening the API would have published the reason
     * an import failed.
     */
    @Test
    void get_thenOpenToAnyReaderOfTheSiteWhileTheAdministrationApiStaysClosed() throws Exception {
        mockMvc.perform(get(path(SITE)))
                .andExpect(status().isOk());

        mockMvc.perform(get(ArchitectureApiPaths.ENVIRONMENTS))
                .andExpect(status().isUnauthorized());
    }

    /**
     * And what being open costs it: nothing on the row that a reader of the site may not have. That an import
     * failed is publishable; why it failed is not, and neither is where it reads from.
     */
    @Test
    void get_thenNeitherTheFailureReasonNorTheUpstreamIsPublished() throws Exception {
        mockMvc.perform(get(path(SITE)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("archrepo.internal.admin.ch"))))
                .andExpect(content().string(not(containsString("answered 503"))))
                .andExpect(content().string(not(containsString("a-content-hash"))));
    }

    /**
     * The default site owns the context root and every other site is served below {@code /site/}, and this
     * resource follows that namespace rather than one of its own - it is answered where the site's own paths
     * are answered, by the resolver that knows the rule.
     */
    @Test
    void get_whenTheRootOrAnotherSite_thenEachSiteAnswersForItself() throws Exception {
        mockMvc.perform(get("/" + DocumentationLiveStatus.FILE_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.site").value("default"));

        mockMvc.perform(get(path("governance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.site").value("governance"));
    }

    private static String path(String site) {
        return "/site/" + site + "/" + DocumentationLiveStatus.FILE_NAME;
    }
}
