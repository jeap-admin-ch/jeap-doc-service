package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePartition;
import ch.admin.bit.jeap.doc.domain.SystemSitePartition;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import org.springframework.stereotype.Component;

/**
 * The documentation of the systems: generated from the architecture model of each environment and from what
 * the teams uploaded, a part per system. It is what a site has that does not set {@code content}.
 * <p>
 * It needs the environment trees - a system is documented as it stands on each stage - so a site without
 * environments cannot have it.
 */
@Component
public class SystemDocumentationContent implements ContentSource {

    public static final String SOURCE_ID = Site.DEFAULT_CONTENT;

    private final SystemSitePartition partition;

    public SystemDocumentationContent(ArchitectureModelSource architectureModel,
                                      CustomDocumentationRepository documentation) {
        this.partition = new SystemSitePartition(architectureModel, documentation);
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public SitePartition partition() {
        return partition;
    }

    @Override
    public boolean publishedByTheArchitectureImport() {
        return true;
    }

    @Override
    public void check(Site site) {
        if (!site.perEnvironment()) {
            throw new IllegalStateException(("The site '%s' has content: %s and per-environment: false. System "
                    + "documentation is a tree per environment; give the site environments or another content "
                    + "source.").formatted(site.id(), SOURCE_ID));
        }
    }
}
