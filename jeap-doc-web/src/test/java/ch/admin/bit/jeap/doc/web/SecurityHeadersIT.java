package ch.admin.bit.jeap.doc.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * The doc service ships its own defaults for the headers of the jEAP web config starter. They only hold if they
 * win over the defaults of the starter itself, which this test pins: the documentation the service serves is
 * self-contained, so the policy must allow no external content - not even the origin of the OAuth2 issuer, which
 * the starter adds to its own default policy. What it does have to allow is what the site generator itself
 * emits: an inline script for the colour mode, and the diagram plugin's engine.
 */
class SecurityHeadersIT extends DocServiceIntegrationTestBase {

    private static final String EXPECTED_CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; " +
            "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void get_whenPathIsNotTheApi_thenCarriesTheContentSecurityPolicyOfTheDocService() throws Exception {
        mockMvc.perform(get("/some-documentation-page.html")
                )
                .andExpect(header().string("Content-Security-Policy", EXPECTED_CONTENT_SECURITY_POLICY));
    }
    /**
     * The jEAP web configuration leaves `/api` prefixes and `-api` suffixes without security headers, matched
     * against the first path segment - and the default site owns the context root, so its environments and
     * everything below them take that segment. The doc service pins both skip lists, so a page whose path
     * merely looks like somebody's API keeps its Content-Security-Policy: a tree served without one is not
     * something to discover later.
     */
    @Test
    void get_whenThePathLooksLikeAnApiOfSomeSystem_thenTheSecurityHeadersAreStillThere() throws Exception {
        mockMvc.perform(get("/orders-api/index.html"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void get_whenThePathStartsWithApiButIsNotTheApi_thenTheSecurityHeadersAreStillThere() throws Exception {
        mockMvc.perform(get("/api-gateway/index.html"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

}
