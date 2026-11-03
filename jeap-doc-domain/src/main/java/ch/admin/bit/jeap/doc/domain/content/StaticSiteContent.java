package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePartition;
import org.springframework.stereotype.Component;

/**
 * A tree of Markdown uploaded for the site itself and published as it is: its folders are the navigation, and
 * nothing is generated into it - no structure template, no system, no chapter. A handbook, a set of guides.
 * <p>
 * It is one tree, so its site has no environments: one upload is the whole site, and nothing says which stage
 * it would describe.
 */
@Component
public class StaticSiteContent implements ContentSource {

    public static final String SOURCE_ID = "static-site";

    private final SitePartition partition = new ShellOnlyPartition();

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public SitePartition partition() {
        return partition;
    }

    @Override
    public boolean takesSiteUploads() {
        return true;
    }

    @Override
    public void check(Site site) {
        if (site.perEnvironment()) {
            throw new IllegalStateException(("The site '%s' has content: %s and environments. A static site is one "
                    + "tree uploaded for the site; set per-environment: false.").formatted(site.id(), SOURCE_ID));
        }
    }
}
