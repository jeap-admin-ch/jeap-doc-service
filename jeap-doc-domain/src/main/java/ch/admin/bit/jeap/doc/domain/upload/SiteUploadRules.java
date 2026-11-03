package ch.admin.bit.jeap.doc.domain.upload;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.content.ContentSources;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Which site takes which upload: a site whose content source takes a tree uploaded for the site itself takes that
 * and nothing else, and every other site takes the documentation of its systems and no site-level tree.
 * <p>
 * One place for the upload, its dry run and the removal of a tree, so that none of them names a content source.
 */
@Component
@RequiredArgsConstructor
public class SiteUploadRules {

    private final DocumentationSites sites;
    private final ContentSources contentSources;

    /**
     * Refuses an upload of this type to this site.
     *
     * @param site the site, or null for the default site
     * @throws InvalidUploadException for a site nobody configures, a site-level tree for a site that takes none,
     *                                or a system's documentation for a site that takes only a tree
     */
    public void requireTheSiteTakes(String site, DocumentationType type) {
        boolean siteLevel = type == DocumentationType.SITE_DOCS;
        boolean takesSiteUploads = takesSiteUploads(site);
        if (siteLevel && !takesSiteUploads) {
            throw InvalidUploadException.invalidValue("site", siteOr(site),
                    "a static site, as the documentation of a site is uploaded to one");
        }
        if (!siteLevel && takesSiteUploads) {
            throw InvalidUploadException.invalidValue("type", type.name(),
                    "SITE_DOCS, as the site %s is a static site and documents no system".formatted(siteOr(site)));
        }
    }

    /**
     * Whether a tree is uploaded for this site itself.
     *
     * @throws InvalidUploadException for a site nobody configures
     */
    public boolean takesSiteUploads(String site) {
        Site configured = sites.find(siteOr(site))
                .orElseThrow(() -> InvalidUploadException.unknownSite(siteOr(site), sites.ids()));
        return contentSources.of(configured).takesSiteUploads();
    }

    private static String siteOr(String site) {
        return site == null || site.isBlank() ? Site.DEFAULT_SITE : site;
    }
}
