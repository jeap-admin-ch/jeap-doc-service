package ch.admin.bit.jeap.doc.web.api.governance;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Governance Service's webhook call.
 * <p>
 * No Governance Service is configured for this context, so an accepted call's import finds
 * {@code GovernanceUpstream} unconfigured and does nothing - these cases are about what the endpoint answers,
 * not about what an import does. That is {@code GovernanceImportTest}'s and {@code GovernanceImportPickupTest}'s
 * business.
 */
class GovernanceWebhookApiIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void notifyOfAScoringRun_withoutAToken_thenUnauthorized() throws Exception {
        mockMvc.perform(post(GovernanceApiPaths.WEBHOOK)
                        .contentType("application/json")
                        .content(body("event-unauthorized")))
                .andExpect(status().isUnauthorized());
    }

    /** The role is checked, not merely that a token is present - any other role must not do. */
    @Test
    void notifyOfAScoringRun_whenTheCallerLacksTheRole_thenForbidden() throws Exception {
        mockMvc.perform(post(GovernanceApiPaths.WEBHOOK)
                        .with(authentication(tokenWithRoles(sitesRole("admin"))))
                        .contentType("application/json")
                        .content(body("event-forbidden")))
                .andExpect(status().isForbidden());
    }

    @Test
    void notifyOfAScoringRun_withTheRole_thenAccepted() throws Exception {
        mockMvc.perform(post(GovernanceApiPaths.WEBHOOK)
                        .with(webhookRole())
                        .contentType("application/json")
                        .content(body("event-accepted")))
                .andExpect(status().isAccepted());
    }

    /** A read-only role does not let a caller report a scoring run either. */
    @Test
    void notifyOfAScoringRun_whenTheCallerMayOnlyReadSites_thenForbidden() throws Exception {
        mockMvc.perform(post(GovernanceApiPaths.WEBHOOK)
                        .with(authentication(tokenWithRoles(sitesRole("read"))))
                        .contentType("application/json")
                        .content(body("event-read-only")))
                .andExpect(status().isForbidden());
    }

    /**
     * A repeated event ID is answered exactly as a new one is - the caller cannot tell a duplicate delivery
     * from a first one, and does not have to.
     */
    @Test
    void notifyOfAScoringRun_whenTheEventIdIsRepeated_thenStillAccepted() throws Exception {
        mockMvc.perform(post(GovernanceApiPaths.WEBHOOK).with(webhookRole())
                        .contentType("application/json")
                        .content(body("event-repeated")))
                .andExpect(status().isAccepted());

        mockMvc.perform(post(GovernanceApiPaths.WEBHOOK).with(webhookRole())
                        .contentType("application/json")
                        .content(body("event-repeated")))
                .andExpect(status().isAccepted());
    }

    @Test
    void notifyOfAScoringRun_withoutAnEventId_thenBadRequest() throws Exception {
        mockMvc.perform(post(GovernanceApiPaths.WEBHOOK).with(webhookRole())
                        .contentType("application/json")
                        .content("{\"scoredAt\":\"2026-11-03T10:47:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    private static RequestPostProcessor webhookRole() {
        return authentication(tokenWithRoles(governanceNotificationsRole("write")));
    }

    private static String body(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"scoredAt\":\"2026-11-03T10:47:00Z\"}";
    }
}
