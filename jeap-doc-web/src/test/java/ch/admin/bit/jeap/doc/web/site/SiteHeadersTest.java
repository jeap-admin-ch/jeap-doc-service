package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.web.configuration.AbstractHeaders;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SiteHeadersTest {

    private static final String POLICY = "default-src 'none'; script-src 'self'";

    private final SiteHeaders headers = new SiteHeaders();

    @Test
    void postProcessHeaders_whenTheFileIsAnSvg_thenItIsSandboxedOnTopOfThePolicy() {
        assertThat(policyOf("/systems/orders/diagram.svg")).isEqualTo(POLICY + "; sandbox");
    }

    @Test
    void postProcessHeaders_whenTheFileIsAPage_thenThePolicyIsUnchanged() {
        assertThat(policyOf("/systems/orders/index.html")).isEqualTo(POLICY);
        assertThat(policyOf("/assets/js/main.js")).isEqualTo(POLICY);
        assertThat(policyOf("/systems/orders/screenshot.png")).isEqualTo(POLICY);
    }

    /** A route of the site is a path without an extension, and a dot in a folder name is not one either. */
    @Test
    void postProcessHeaders_whenThePathHasNoExtension_thenThePolicyIsUnchanged() {
        assertThat(policyOf("/systems/orders/building-block-view/")).isEqualTo(POLICY);
        assertThat(policyOf("/systems/orders.v2/glossary")).isEqualTo(POLICY);
    }

    @Test
    void postProcessHeaders_whenTheExtensionIsUpperCase_thenItIsStillADocument() {
        assertThat(policyOf("/uploaded/Diagram.SVG")).isEqualTo(POLICY + "; sandbox");
    }

    /**
     * The starter leaves out the policy for the paths it skips. Sandboxing is then the whole answer rather
     * than an addition to one - an uploaded document is contained either way.
     */
    @Test
    void postProcessHeaders_whenThereIsNoPolicyToAddTo_thenTheSandboxIsTheWholePolicy() {
        Map<String, String> withoutPolicy = new HashMap<>();

        headers.postProcessHeaders(withoutPolicy, "GET", "/uploaded/diagram.svg");

        assertThat(withoutPolicy.get(AbstractHeaders.CONTENT_SECURITY_POLICY)).isEqualTo("sandbox");
    }

    @Test
    void postProcessHeaders_whenThePathIsAMicrosite_thenItIsSandboxedWithAnOriginOfItsOwn() {
        Map<String, String> sent = headersOf("/microsites/orders/arc42/6-runtime-view/inbox/index.html");

        assertThat(sent.get(AbstractHeaders.CONTENT_SECURITY_POLICY))
                .startsWith("sandbox allow-scripts")
                .describedAs("with it, a framed page could remove the sandbox and reload itself")
                .doesNotContain("allow-same-origin");
        assertThat(sent.get("Cross-Origin-Resource-Policy")).isEqualTo("cross-origin");
    }

    /**
     * A microsite fetches its own files from an opaque origin, so they are cross-origin requests. Nothing
     * else this service answers is, and a rule that drifted here is how /api would start answering them.
     */
    @Test
    void postProcessHeaders_thenOnlyAMicrositeAnswersCrossOrigin() {
        assertThat(headersOf("/microsites/orders/arc42/6-runtime-view/inbox/assets/app.js"))
                .containsEntry("Access-Control-Allow-Origin", "*");
        assertThat(headersOf("/site/governance/microsites/orders/arc42/6-runtime-view/inbox/"))
                .describedAs("below a site of its own too")
                .containsEntry("Access-Control-Allow-Origin", "*");
        assertThat(headersOf("/systems/orders/index.html"))
                .doesNotContainKey("Access-Control-Allow-Origin");
        assertThat(headersOf("/api/docs/custom/sets"))
                .doesNotContainKey("Access-Control-Allow-Origin");
    }

    /**
     * <b>The headers and the router agree on what a microsite path is.</b> A path under the segment that is too
     * short to name a microsite is never served as one - it falls through to the site's not-found page - so it
     * is not given the microsite's policy and its cross-origin header either.
     */
    @Test
    void postProcessHeaders_whenThePathIsTooShortToNameAMicrosite_thenItIsNotTreatedAsOne() {
        for (String path : new String[]{"/microsites", "/microsites/", "/microsites/orders",
                "/microsites/orders/arc42/6-runtime-view", "/microsites/orders/components/intake/arc42/x",
                "/site/governance/microsites/", "/site/governance/microsites/orders/arc42"}) {
            assertThat(headersOf(path)).describedAs(path).doesNotContainKey("Access-Control-Allow-Origin");
        }
    }

    /**
     * But a path with the shape of one keeps the microsite's headers even when nothing is published under it:
     * a framed microsite asks for its missing asset from its opaque origin, and the 404 has to reach it.
     */
    @Test
    void postProcessHeaders_whenThePathHasTheShapeOfAMicrosite_thenItIsTreatedAsOneWhetherOrNotItExists() {
        assertThat(headersOf("/microsites/orders/arc42/6-runtime-view/inbox"))
                .containsEntry("Access-Control-Allow-Origin", "*");
        assertThat(headersOf("/microsites/orders/components/intake/arc42/6-runtime-view/inbox/missing.css"))
                .containsEntry("Access-Control-Allow-Origin", "*");
    }

    private Map<String, String> headersOf(String path) {
        Map<String, String> sent = new HashMap<>(Map.of(AbstractHeaders.CONTENT_SECURITY_POLICY, POLICY));
        headers.postProcessHeaders(sent, "GET", path);
        return sent;
    }

    private String policyOf(String path) {
        Map<String, String> sent = new HashMap<>(Map.of(AbstractHeaders.CONTENT_SECURITY_POLICY, POLICY));
        headers.postProcessHeaders(sent, "GET", path);
        return sent.get(AbstractHeaders.CONTENT_SECURITY_POLICY);
    }
}
