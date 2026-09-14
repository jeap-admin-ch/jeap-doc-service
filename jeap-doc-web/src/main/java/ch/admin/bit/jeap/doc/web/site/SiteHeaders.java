package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.web.configuration.AbstractHeaders;
import ch.admin.bit.jeap.web.configuration.HttpHeaderFilterPostProcessor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adjusts the security headers of the jEAP web config starter for what a site serves.
 * <p>
 * Three kinds of path, and they are told apart by the path alone:
 * <ul>
 * <li><b>A microsite</b> is documentation this service did not write, so it is served with a policy that
 *     sandboxes it: an opaque origin, no access to the documentation site around it, and nothing it may
 *     navigate. The sandbox is on the response and not only on the iframe, so a file opened directly is as
 *     contained as a framed one.</li>
 * <li><b>An uploaded file a browser renders as a document</b> - an SVG - is sandboxed on top of the site's own
 *     policy: it gets this origin when it is opened directly, and its script would otherwise run here.</li>
 * <li><b>Everything else</b> keeps the policy the starter was configured with.</li>
 * </ul>
 * <b>{@code Access-Control-Allow-Origin} is sent under the microsite prefix and nowhere else.</b> A microsite
 * fetches its own files from an opaque origin, so those requests are cross-origin and carry {@code Origin:
 * null}; every other path of this service answers no cross-origin request at all, and a rule that drifted here
 * is how {@code /api} would start answering them.
 */
@Component
class SiteHeaders implements HttpHeaderFilterPostProcessor {

    /**
     * The extensions a browser renders as a document rather than as an image or a download. Only {@code svg}
     * can reach this from an upload today - it is the one picture format that carries script - but a policy
     * that named a single extension would be read as being about SVG rather than about documents.
     */
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("svg", "xml", "xhtml", "xsl", "xslt");

    /**
     * What a microsite is served with - the sandbox above all, which is what gives it an origin of its own.
     * {@code allow-same-origin} is never among the tokens: with it, a framed document could simply remove the
     * iframe's sandbox attribute and reload itself.
     */
    private static final String MICROSITE_POLICY =
            "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox allow-downloads; "
            + "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'self'; "
            + "base-uri 'self'; form-action 'none'";

    @Override
    public void postProcessHeaders(Map<String, String> headers, String method, String path) {
        if (isAMicrosite(path)) {
            headers.put(AbstractHeaders.CONTENT_SECURITY_POLICY, MICROSITE_POLICY);
            // The microsite's own files are fetched from an opaque origin, which makes every one of them a
            // cross-origin request carrying 'Origin: null'. Without this a module script is refused rather
            // than run, and a font is refused rather than shown.
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cross-Origin-Resource-Policy", "cross-origin");
            headers.put(AbstractHeaders.REFERRER_POLICY, "no-referrer");
            headers.put(AbstractHeaders.X_FRAME_OPTIONS, "sameorigin");
            return;
        }
        if (rendersAsADocument(path)) {
            headers.merge(AbstractHeaders.CONTENT_SECURITY_POLICY, "sandbox",
                    (policy, sandbox) -> policy + "; " + sandbox);
        }
    }

    /**
     * Whether the path is one of an uploaded microsite: at the top of a site, or below {@code /site/<id>/} for
     * a site that is not the default one - and with the shape the router resolves, whether or not anything is
     * published under it. A framed microsite asks for a missing asset from its opaque origin, so its 404 keeps
     * the microsite's headers; a path too short to name one is not a microsite at all.
     */
    private static boolean isAMicrosite(String path) {
        String rest = path.startsWith("/") ? path.substring(1) : path;
        String sitePrefix = Site.SITE_SEGMENT + "/";
        if (rest.startsWith(sitePrefix)) {
            int endOfSiteId = rest.indexOf('/', sitePrefix.length());
            if (endOfSiteId > 0 && MicrositePath.keyPartsOf(rest.substring(endOfSiteId + 1)) != null) {
                return true;
            }
        }
        return MicrositePath.keyPartsOf(rest) != null;
    }

    private static boolean rendersAsADocument(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash && DOCUMENT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
