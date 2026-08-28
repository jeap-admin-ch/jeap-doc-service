package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.PublishedDocumentation;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Decides which file of which published site a request is asking for, and answers when there is none.
 * <p>
 * It is registered as the <b>last</b> handler in the chain (see {@link SiteWebConfiguration}) and therefore
 * answers only when nothing else does - not an annotated controller, and not a resource handler. That matters:
 * an annotated {@code @GetMapping("/**")} is matched at order 0, ahead of Spring's resource handlers, and would
 * swallow the Swagger UI's assets.
 * <p>
 * What it serves is whatever the newest successful build of a site published: the generator and the web server
 * share a bucket and a row, and nothing else. How a file is written to the reader is
 * {@link SiteContentResponse}'s question, and what a site that is not there yet answers is
 * {@link NotGeneratedYetResponse}'s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteRequestHandler implements HttpRequestHandler {

    private final SitePathResolver resolver;
    private final PublishedDocumentation documentation;

    /**
     * Only GET and HEAD. Everything else that reaches here is a method nothing serves - answering a misdirected
     * {@code PUT} with the documentation site would tell a pipeline the documentation is missing rather than
     * what is actually wrong with its request.
     */
    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            response.sendError(HttpStatus.METHOD_NOT_ALLOWED.value());
            return;
        }

        String path = UriUtils.decode(pathWithinApplication(request), StandardCharsets.UTF_8);
        if (isSuspicious(path)) {
            // The container normalises and would already have refused what could escape the application root.
            // Checked here as well because the key this becomes is built by string concatenation, over a bucket
            // that also holds the uploaded bundles - and the rule should not live in another component.
            log.debug("Refusing the path {}: it is not a path within a published site.", path);
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        Optional<SitePath> resolved = resolver.resolve(path);
        if (resolved.isEmpty()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        serve(resolved.get(), path, request, response);
    }

    private void serve(SitePath sitePath, String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String siteId = sitePath.site().id();
        if (!documentation.isPublished(siteId)) {
            NotGeneratedYetResponse.writeTo(siteId, response);
            return;
        }

        Optional<StoredObject> object = documentation.open(siteId, sitePath.file());
        if (object.isPresent()) {
            SiteContentResponse.write(object.get(), sitePath.file(), HttpStatus.OK, request, response);
            return;
        }
        if (isARouteMissingItsTrailingSlash(sitePath, path, siteId)) {
            redirectToTheCanonicalForm(request, response);
            return;
        }
        // The site is recorded as published and its own front page is not there. That is not a wrong URL - the
        // objects are gone, expired by the bucket's lifecycle rule after a site went too long without a
        // successful build - and answering 404 would send an operator looking for a typo.
        if (sitePath.isSiteRoot()) {
            NotGeneratedYetResponse.writeTo(siteId, response);
            return;
        }
        notFound(siteId, request, response);
    }

    /**
     * A path with no extension is a route, and the site is generated with a trailing slash on every one of them:
     * sending the reader to the canonical form is what makes a hand-typed URL work. The canonical form is checked
     * first, because a 301 is cached for ever - a typo that redirected would keep redirecting from the reader's
     * cache long after the site had changed. Checked with {@code exists()} rather than {@code open()}, which
     * would hand back a connection nobody closes.
     */
    private boolean isARouteMissingItsTrailingSlash(SitePath sitePath, String path, String siteId) {
        return sitePath.looksLikeADirectory() && !path.endsWith("/")
               && documentation.exists(siteId, sitePath.file() + "/" + SitePath.INDEX);
    }

    private static void redirectToTheCanonicalForm(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
        // The query goes with it: /search?q=upload is a link readers share, and a redirect that dropped it would
        // land them on an empty search box - permanently, since the redirect is cached.
        String query = request.getQueryString();
        response.setHeader(HttpHeaders.LOCATION,
                request.getRequestURI() + "/" + (query == null ? "" : "?" + query));
    }

    /**
     * Whether the decoded path is one no published site can hold: a dot segment, a backslash, a NUL, or a
     * percent sign that survived decoding - all of which would only ever be an attempt to leave the prefix.
     */
    static boolean isSuspicious(String path) {
        return path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0 || path.contains("%");
    }

    /**
     * The site's own not-found page, with the status that says what it is. An empty 404 is the fallback, so that
     * a site published before it had one does not answer with a stack trace.
     */
    private void notFound(String siteId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Optional<StoredObject> page = documentation.open(siteId, SitePath.NOT_FOUND);
        if (page.isPresent()) {
            SiteContentResponse.write(page.get(), SitePath.NOT_FOUND, HttpStatus.NOT_FOUND, request, response);
        } else {
            response.sendError(HttpStatus.NOT_FOUND.value());
        }
    }

    /**
     * The request path with the context path removed - what the site is addressed by, whatever the service is
     * deployed under.
     */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }
}
