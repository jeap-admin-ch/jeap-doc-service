package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
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

    /** What tells a component's or a library's microsite from a system's own. */
    private static final String COMPONENTS = MicrositePath.COMPONENTS;
    private static final String LIBRARIES = MicrositePath.LIBRARIES;

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
     * The microsite a path addresses, if it names one that could exist.
     * <p>
     * <b>A segment of its own, below the site.</b> A microsite is not part of what a build publishes, so it
     * is served from {@code /microsites/…} rather than from anywhere a generated page could be - and no
     * environment of the default site may be called that, which {@code DocumentationSites} refuses.
     * <p>
     * Three shapes, one per kind of subject. A component's and a library's name the kind, because a
     * component called {@code arc42} would otherwise be indistinguishable from a system's own microsite
     * following that template:
     * <pre>
     * /microsites/&lt;system&gt;/components/&lt;name&gt;/&lt;template&gt;/&lt;location&gt;/&lt;topic&gt;/&lt;file&gt;
     * /microsites/&lt;system&gt;/libraries/&lt;name&gt;/&lt;template&gt;/&lt;location&gt;/&lt;topic&gt;/&lt;file&gt;
     * /microsites/&lt;system&gt;/&lt;template&gt;/&lt;location&gt;/&lt;topic&gt;/&lt;file&gt;
     * </pre>
     * Whether such a set is published is {@code PublishedMicrosites}' question; this only reads the path.
     */
    public Optional<MicrositePath> resolveMicrosite(String path) {
        String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        return namedSiteRest(withoutLeadingSlash)
                .map(rest -> micrositeOf(rest.site(), rest.path()))
                .orElseGet(() -> sites.find(Site.DEFAULT_SITE)
                        .flatMap(site -> micrositeOf(site, withoutLeadingSlash)));
    }

    private Optional<MicrositePath> micrositeOf(Site site, String rest) {
        return MicrositePath.keyPartsOf(rest).map(segments -> micrositeOf(site, segments));
    }

    private MicrositePath micrositeOf(Site site, String[] segments) {
        boolean named = MicrositePath.isNamed(segments);
        int parts = MicrositePath.partsOf(segments);
        SubjectKind kind = kindOf(segments, named);
        String name = named ? segments[2] : null;
        int at = named ? 3 : 1;
        CustomSetKey key = new CustomSetKey(site.id(), kind, segments[0], name, SourceFormat.HTML,
                segments[at], segments[at + 1], segments[at + 2]);
        String file = String.join("/", java.util.Arrays.copyOfRange(segments, parts, segments.length));
        return new MicrositePath(site, key, fileOfMicrosite(file));
    }

    private static SubjectKind kindOf(String[] segments, boolean named) {
        if (!named) {
            return SubjectKind.SYSTEM;
        }
        return COMPONENTS.equals(segments[1]) ? SubjectKind.COMPONENT : SubjectKind.LIBRARY;
    }

    /** A microsite is opened at its entry point, so a path that names no file addresses the index. */
    private static String fileOfMicrosite(String file) {
        if (file.isEmpty()) {
            return MicrositePath.INDEX;
        }
        return file.endsWith("/") ? file + MicrositePath.INDEX : file;
    }

    /** The site named below {@code /site/} and what follows it, for a path that names one. */
    private Optional<SiteAndPath> namedSiteRest(String path) {
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
        return sites.find(id).map(site -> new SiteAndPath(site, rest));
    }

    private record SiteAndPath(Site site, String path) {
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
