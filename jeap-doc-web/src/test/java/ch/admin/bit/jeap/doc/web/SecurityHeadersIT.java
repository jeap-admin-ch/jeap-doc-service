package ch.admin.bit.jeap.doc.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * The doc service ships its own defaults for the headers of the jEAP web config starter. They only hold if they
 * win over the defaults of the starter itself, which this test pins: the documentation the service serves is
 * self-contained, so the policy must allow no external content - not even the origin of the OAuth2 issuer, which
 * the starter adds to its own default policy.
 */
class SecurityHeadersIT extends DocServiceIntegrationTestBase {

    private static final String EXPECTED_CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
            "font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void get_whenPathIsNotTheApi_thenCarriesTheContentSecurityPolicyOfTheDocService() throws Exception {
        mockMvc.perform(get("/some-documentation-page.html")
                        .with(authentication(tokenWithRoles(docsRole("read")))))
                .andExpect(header().string("Content-Security-Policy", EXPECTED_CONTENT_SECURITY_POLICY));
    }
}
