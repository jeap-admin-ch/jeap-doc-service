package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Works out which documentation site a request is for, and which file of it.
 * <p>
 * The default site owns the context root and every other site takes a path segment of its own. A site may not be
 * named after an environment of the default site, which is checked while the service starts - otherwise both
 * would be served under the same segment and which of them answered would depend on the order of the checks.
 */
@Component
@RequiredArgsConstructor
public class SitePathResolver {

    private final DocumentationSites sites;

    /**
     * The site and the file the given path within the service is for, if any site can serve it.
     *
     * @param path the request path with the context path already removed, starting with a slash
     */
    public Optional<SitePath> resolve(String path) {
        String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        int firstSegmentEnd = withoutLeadingSlash.indexOf('/');
        String firstSegment = firstSegmentEnd < 0 ? withoutLeadingSlash
                : withoutLeadingSlash.substring(0, firstSegmentEnd);

        // A first segment naming a site other than the default one addresses that site; anything else is a path
        // within the default site, which owns the root.
        if (!firstSegment.isEmpty() && !Site.DEFAULT_SITE.equals(firstSegment)) {
            Optional<Site> named = sites.find(firstSegment);
            if (named.isPresent()) {
                String rest = firstSegmentEnd < 0 ? "" : withoutLeadingSlash.substring(firstSegmentEnd + 1);
                return Optional.of(new SitePath(named.get(), fileOf(rest)));
            }
        }
        return sites.find(Site.DEFAULT_SITE).map(site -> new SitePath(site, fileOf(withoutLeadingSlash)));
    }

    /**
     * The file a path addresses. The site is generated with a trailing slash on every route, so a path that ends
     * in one - or is empty - is a directory and its file is the {@code index.html} inside it.
     */
    private static String fileOf(String path) {
        if (path.isEmpty()) {
            return SitePath.INDEX;
        }
        return path.endsWith("/") ? path + SitePath.INDEX : path;
    }
}
