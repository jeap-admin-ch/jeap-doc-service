package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Where a site is published, as the generated site itself has to know it.
 * <p>
 * The base path is not a property: it is the context path of the service, read once. A path that has to agree
 * with another value is derived rather than written twice - the same reasoning that keeps the swallow size of
 * the servlet container in step with the maximum upload size.
 */
@Component
public class SiteUrls {

    /** A URL path separator, not a file one: these values end up in the browser, never on a filesystem. */
    private static final String URL_SEPARATOR = "/";

    private final String origin;
    private final String contextPath;

    public SiteUrls(PublicationProperties publication,
                    @Value("${server.servlet.context-path:}") String contextPath) {
        // Not optional, and checked here rather than at the first build: the generated site carries this origin
        // in its sitemap and its metadata, and the site generator refuses an empty one - which would otherwise
        // surface minutes into a build instead of in the deployment.
        if (!StringUtils.hasText(publication.getUrl())) {
            throw new IllegalStateException(
                    "jeap.doc.publication.url is not configured. It is the origin the documentation is published "
                    + "under, without a path - for example https://doc.example.ch - and the generated site needs "
                    + "it for its sitemap and its page metadata.");
        }
        this.origin = requireAnOrigin(publication.getUrl());
        this.contextPath = normalize(contextPath);
    }

    /**
     * The value has to be an origin and nothing more. A path belongs in the context path, and one given here
     * would be doubled - the generated sitemap and every canonical URL would carry it twice - or would fail the
     * Docusaurus build minutes into a run.
     */
    private static String requireAnOrigin(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "jeap.doc.publication.url is not a URL: %s".formatted(url), e);
        }
        boolean origin = uri.isAbsolute() && uri.getHost() != null
                         && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                         && uri.getQuery() == null && uri.getFragment() == null;
        if (!origin) {
            throw new IllegalStateException(("jeap.doc.publication.url is '%s'. It is the origin the "
                                             + "documentation is published under and nothing more - for example "
                                             + "https://doc.example.ch. A path below it belongs in "
                                             + "server.servlet.context-path.").formatted(url));
        }
        // Normalised here and nowhere else: validating the stripped value and then publishing the unstripped
        // one would let whitespace through the check and fail the site generator instead.
        return stripTrailingSlash(uri.toString());
    }

    /**
     * The origin the documentation is published under, for the sitemap and the page metadata.
     */
    public String url() {
        return origin;
    }

    /**
     * The path a site is served under, with a trailing slash, as the site generator needs it: the context path
     * of the service, and below it the site - except the default site, which owns the context root.
     */
    public String baseUrl(Site site) {
        return contextPath + site.routePrefix() + URL_SEPARATOR;
    }

    /**
     * The context path of the service, without a trailing slash and empty when it is the root.
     */
    public String contextPath() {
        return contextPath;
    }

    private static String normalize(String configured) {
        if (!StringUtils.hasText(configured) || URL_SEPARATOR.equals(configured)) {
            return "";
        }
        String path = configured.startsWith(URL_SEPARATOR) ? configured : URL_SEPARATOR + configured;
        return stripTrailingSlash(path);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith(URL_SEPARATOR) ? value.substring(0, value.length() - 1) : value;
    }
}
