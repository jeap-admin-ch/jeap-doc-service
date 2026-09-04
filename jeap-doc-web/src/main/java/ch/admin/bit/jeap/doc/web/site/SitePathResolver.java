package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Works out which documentation site a request is for, and which file of it.
 * <p>
 * The default site owns the context root, and every other site is served below {@code /site/<id>/} - see
 * {@link Site#SITE_SEGMENT}. The two namespaces are therefore separate: whatever a site is called, it cannot
 * take the URLs of the default site's environments or of anything this service answers on itself, and this
 * resolver needs no list of names to tell the cases apart.
 */
@Component
@RequiredArgsConstructor
public class SitePathResolver {

    private final DocumentationSites sites;

    /**
     * The site and the file the given path within the service is for, if any site can serve it.
     * <p>
     * {@code /site/<id>/…} addresses that site when it is configured. Everything else - including
     * {@code /site/} itself, an id nobody configured, and a first segment that happens to be the name of a
     * site - is a path within the default site, which owns the root.
     *
     * @param path the request path with the context path already removed, starting with a slash
     */
    public Optional<SitePath> resolve(String path) {
        String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        return namedSite(withoutLeadingSlash)
                .or(() -> sites.find(Site.DEFAULT_SITE)
                        .map(site -> new SitePath(site, fileOf(withoutLeadingSlash))));
    }

    /**
     * The site addressed below {@code /site/}, and the file within it, when the path names one that is
     * configured.
     * <p>
     * The default site is <b>not</b> reachable this way. It owns the root, and serving it under two paths as
     * well would give every one of its pages a second URL - which is a duplicate for a search engine and a
     * second base URL the generated site knows nothing about.
     */
    private Optional<SitePath> namedSite(String path) {
        String prefix = Site.SITE_SEGMENT + "/";
        if (!path.startsWith(prefix)) {
            return Optional.empty();
        }
        String belowSegment = path.substring(prefix.length());
        int idEnd = belowSegment.indexOf('/');
        String id = idEnd < 0 ? belowSegment : belowSegment.substring(0, idEnd);
        if (id.isEmpty() || Site.DEFAULT_SITE.equals(id)) {
            return Optional.empty();
        }
        String rest = idEnd < 0 ? "" : belowSegment.substring(idEnd + 1);
        return sites.find(id).map(site -> new SitePath(site, fileOf(rest)));
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
