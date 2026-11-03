package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.SitePartition;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;

import java.util.List;
import java.util.Optional;

/**
 * A site that is one part, its shell, carrying the whole site. It is the partition of a content source whose
 * site is small enough to be built at once and documents no systems of its own.
 */
public final class ShellOnlyPartition implements SitePartition {

    @Override
    public String axis(Site site) {
        return "none";
    }

    @Override
    public List<SitePart> partsOf(Site site) {
        return List.of(shellOf(site));
    }

    @Override
    public Optional<SitePart> partOf(Site site, String partId) {
        return SitePart.SHELL.equals(partId) ? Optional.of(shellOf(site)) : Optional.empty();
    }

    @Override
    public SitePart shellOf(Site site) {
        return new SitePart(PartKey.shellOf(site.id()), "the site itself", "", false,
                site.environments().stream().map(SiteEnvironment::id).toList(), List.of());
    }

    /** The shell: it carries the whole site, so whatever was uploaded to the site is in it. */
    @Override
    public List<SitePart> partsDocumenting(Site site, String environment, String systemName) {
        return List.of(shellOf(site));
    }
}
