package ch.admin.bit.jeap.doc.web.uiapi;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.doc.web.api.architecture.ArchitectureApiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The governance excerpt a page reads: open, answered from what the import stored, revalidated by a weak entity
 * tag - and the API below {@code /api} as closed as it was.
 * <p>
 * The Governance Service this context is configured with is a port nothing listens on: an answer at all says it
 * was not asked. What the class stores it removes again, since every class of this module shares the database.
 */
class GovernanceExcerptIT extends DocServiceIntegrationTestBase {

    private static final String SITE = "govexcerpt";
    private static final String EXCERPT = "/ui-api/excerpts/governance/systems?system=";
    private static final LocalDate TODAY = LocalDate.now();

    /** Every character a path segment could not carry past the firewall. */
    private static final String ODD_NAME = "Orders/EU; 100% \\ done";
    private static final String ODD_NAME_JSON = ODD_NAME.replace("\\", "\\\\");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GovernanceResourceRepository resources;

    @DynamicPropertySource
    static void governanceProperties(DynamicPropertyRegistry registry) {
        registry.add("jeap.doc.sites." + SITE + ".title", () -> "Governance");
        registry.add("jeap.doc.sites." + SITE + ".per-environment", () -> "false");
        registry.add("jeap.doc.sites." + SITE + ".content", () -> "governance");
        registry.add("jeap.doc.sites." + SITE + ".publish-on-upload", () -> "false");
        registry.add("jeap.doc.governance.url", () -> "http://localhost:1/governance");
        registry.add("jeap.doc.governance.client-registration", () -> "governance");
        registry.add("jeap.doc.governance.import.cron", () -> "-");
        registry.add("spring.security.oauth2.client.registration.governance.client-id", () -> "doc-service");
        registry.add("spring.security.oauth2.client.registration.governance.client-secret", () -> "secret");
        registry.add("spring.security.oauth2.client.registration.governance.authorization-grant-type",
                () -> "client_credentials");
        registry.add("spring.security.oauth2.client.registration.governance.provider", () -> "governance");
        registry.add("spring.security.oauth2.client.provider.governance.token-uri",
                () -> "http://localhost:1/auth/realms/test/protocol/openid-connect/token");
    }

    @BeforeEach
    void storeTheGovernanceData() {
        store(GovernanceResourceKind.SYSTEMS_INDEX, null, """
                {"scoredAt": "2026-10-22T06:45:21Z", "trendPeriodDays": 30,
                 "systems": [{"name": "Inspecziun", "score": 86, "trend": "STABLE"},
                             {"name": "%s", "score": 70, "trend": "UP"}]}
                """.formatted(ODD_NAME_JSON));
        store(GovernanceResourceKind.RULES_INDEX, null, """
                {"scoredAt": "2026-10-22T06:45:21Z",
                 "rules": [{"ruleId": "signed-messages", "name": "Component Consumes Signed Messages",
                            "active": true, "weight": 3}]}
                """);
        store(GovernanceResourceKind.RULE, "signed-messages", """
                {"ruleId": "signed-messages", "name": "Component Consumes Signed Messages", "active": true}
                """);
        store(GovernanceResourceKind.SYSTEM, "Inspecziun", """
                {"name": "Inspecziun", "score": 86, "trend": "STABLE", "history": %s,
                 "components": [{"name": "inspecziun-borderpanel-scs", "score": 78, "trend": "DOWN",
                                 "history": [],
                                 "ruleStates": [
                                   {"ruleId": "signed-messages", "state": "FAIL",
                                    "comment": "Signature is not enforced for consumption",
                                    "details": ["topic a"], "lastStatusChange": "2026-10-20T06:00:00Z"},
                                   {"ruleId": "other", "state": "OK"},
                                   {"ruleId": "paused", "state": "PAUSED"}]}]}
                """.formatted(history(20)));
        store(GovernanceResourceKind.SYSTEM, ODD_NAME, """
                {"name": "%s", "score": 70, "trend": "UP", "history": [], "components": []}
                """.formatted(ODD_NAME_JSON));
    }

    @AfterEach
    void removeIt() {
        List<GovernanceResourceRef> stored = new ArrayList<>();
        for (GovernanceResourceKind kind : GovernanceResourceKind.values()) {
            stored.addAll(resources.findRefs(kind));
        }
        resources.remove(stored);
    }

    @Test
    void get_withoutAToken_thenTheExcerptWithLinksIntoTheGovernanceSite() throws Exception {
        String scoredAt = OffsetDateTime.parse("2026-10-22T06:45:21Z")
                .atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        mockMvc.perform(get(EXCERPT + "Inspecziun"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.system").value("Inspecziun"))
                .andExpect(jsonPath("$.link").value("/site/" + SITE + "/systems/inspecziun/"))
                .andExpect(jsonPath("$.rulesOverview").value("/site/" + SITE + "/rules/"))
                .andExpect(jsonPath("$.scoredAt").value(scoredAt))
                .andExpect(jsonPath("$.score").value(86))
                .andExpect(jsonPath("$.history.length()").value(14))
                .andExpect(jsonPath("$.history[13].day").value(TODAY.toString()))
                .andExpect(jsonPath("$.components[0].link")
                        .value("/site/" + SITE + "/systems/inspecziun/inspecziun-borderpanel-scs/"))
                .andExpect(jsonPath("$.components[0].compliant").value(1))
                .andExpect(jsonPath("$.components[0].pausedOrDisabled").value(1))
                .andExpect(jsonPath("$.components[0].failingRules[0].label")
                        .value("Component Consumes Signed Messages"))
                .andExpect(jsonPath("$.components[0].failingRules[0].details[0]").value("topic a"))
                .andExpect(jsonPath("$.components[0].failingRules[0].link")
                        .value("/site/" + SITE + "/rules/signed-messages/"));
    }

    @Test
    void get_thenAWeakEntityTagRevalidatedOnEveryUse() throws Exception {
        MvcResult first = mockMvc.perform(get(EXCERPT + "Inspecziun"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, startsWith("W/\"sha256:")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andReturn();
        String tag = first.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get(EXCERPT + "Inspecziun").header(HttpHeaders.IF_NONE_MATCH, tag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, tag))
                .andExpect(content().bytes(new byte[0]));

        // A new scoring run changes the answer at once - no page is rebuilt for it.
        store(GovernanceResourceKind.SYSTEMS_INDEX, null, """
                {"scoredAt": "2026-10-22T10:45:21Z", "trendPeriodDays": 30,
                 "systems": [{"name": "Inspecziun", "score": 90, "trend": "UP"}]}
                """);
        mockMvc.perform(get(EXCERPT + "Inspecziun").header(HttpHeaders.IF_NONE_MATCH, tag))
                .andExpect(status().isOk());
    }

    @Test
    void get_whenTheSystemIsNotScored_thenNoContentRatherThanAnError() throws Exception {
        mockMvc.perform(get(EXCERPT + "Unknown"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void get_whenTheNameHoldsWhatAPathSegmentCannot_thenItsExcerpt() throws Exception {
        mockMvc.perform(get(UiApiPaths.GOVERNANCE_EXCERPT).queryParam("system", ODD_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system").value(ODD_NAME))
                .andExpect(jsonPath("$.score").value(70));
    }

    @Test
    void get_whenNoSystemIsNamed_thenNoContentRatherThanAnError() throws Exception {
        mockMvc.perform(get(UiApiPaths.GOVERNANCE_EXCERPT))
                .andExpect(status().isNoContent());
    }

    @Test
    void writing_thenRefusedWithoutReachingAnything() throws Exception {
        int post = mockMvc.perform(post(EXCERPT + "Inspecziun")).andReturn().getResponse().getStatus();
        int put = mockMvc.perform(put(EXCERPT + "Inspecziun")).andReturn().getResponse().getStatus();

        assertThat(List.of(post, put)).allSatisfy(status -> assertThat(status).isIn(401, 403));
    }

    @Test
    void theApi_thenStillNeedsAToken() throws Exception {
        mockMvc.perform(get(ArchitectureApiPaths.ENVIRONMENTS))
                .andExpect(status().isUnauthorized());
    }

    private static String history(int days) {
        List<String> entries = new ArrayList<>();
        for (int back = days - 1; back >= 0; back--) {
            entries.add("{\"day\": \"%s\", \"score\": %d}".formatted(TODAY.minusDays(back), 80 + back % 5));
        }
        return "[" + String.join(", ", entries) + "]";
    }

    private void store(GovernanceResourceKind kind, String key, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        resources.store(new GovernanceResource(kind, key, "\"" + kind + "-" + json.hashCode() + "\"", bytes,
                bytes.length, Instant.now()));
    }
}
