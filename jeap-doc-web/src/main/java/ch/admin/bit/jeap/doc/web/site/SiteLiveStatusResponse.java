package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DocumentationLiveStatus;
import ch.admin.bit.jeap.doc.domain.DocumentationProvenance;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The live status of one documentation site, answered from the service's own records instead of from what a
 * build published.
 * <p>
 * <b>It is a path of the site, and that is what decides who may read it.</b> The page describing the
 * documentation is served to anyone who can reach the service, and the statements this carries are the ones
 * that page used to print - so they belong to the same resource and the same rule. Everything below
 * {@code /api} is administration and needs a token; this is not there, and putting it there would put a
 * bearer token in front of a table on a public page. What may be published is decided in
 * {@link DocumentationProvenance}, as it is for the page itself.
 * <p>
 * Answered by {@link SiteRequestHandler} rather than by a controller of its own, so that which site a request
 * addresses is worked out by {@link SitePathResolver} - the one place that knows that the default site owns
 * the context root and every other site is served below {@code /site/}.
 */
@Component
@RequiredArgsConstructor
class SiteLiveStatusResponse {

    private final DocumentationProvenance provenance;

    /**
     * The application's mapper, unlike the one the site generator writes its files with: this is a response of
     * the service, and it should render an instant the way the rest of the API does.
     */
    private final ObjectMapper json;

    /** Whether this path is the live status of its site rather than a file of the published output. */
    static boolean isAddressedBy(SitePath path) {
        return DocumentationLiveStatus.FILE_NAME.equals(path.file());
    }

    /**
     * Writes the live status of the site, or 404 where the site has gone out of the configuration between the
     * resolution of the path and this.
     * <p>
     * <b>Never cached.</b> The whole reason this is not part of the published site is that it changes without
     * the documentation changing, and a cached copy would be the frozen page again with an extra step.
     */
    void writeTo(String siteId, HttpServletResponse response) throws IOException {
        Optional<DocumentationLiveStatus> status = provenance.liveStatusOf(siteId);
        if (status.isEmpty()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        response.setStatus(HttpStatus.OK.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write(json.writeValueAsString(status.get()));
    }
}
