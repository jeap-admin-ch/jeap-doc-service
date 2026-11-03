package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePartition;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The governance reports: a site of their own, shell only, generated from what the governance import stores -
 * no system, no upload, no structure template.
 * <p>
 * Like {@link StaticSiteContent}, it is partitioned as shell only: one part, one build. Unlike it, nothing is
 * ever uploaded for this content - {@link #takesSiteUploads()} stays the interface's default, {@code false} -
 * and the shell is asked for by the governance import rather than by an upload.
 */
@Component
@RequiredArgsConstructor
public class GovernanceContent implements ContentSource {

    public static final String SOURCE_ID = "governance";

    private final SitePartition partition = new ShellOnlyPartition();

    private final GovernanceUpstream upstream;

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public SitePartition partition() {
        return partition;
    }

    @Override
    public boolean publishedByTheGovernanceImport() {
        return true;
    }

    /**
     * A site of this content with no Governance Service configured would never be generated - the import that
     * is its only trigger never runs - and would serve nothing for ever, which is a configuration mistake and
     * not a legitimate instance the way a site with no architecture repository is.
     * <p>
     * Environments are refused too, the same way {@link StaticSiteContent} refuses them: the Governance
     * Service is read once per instance, not once per stage, so there is nothing an environment of this site
     * would ever show that another one would not.
     */
    @Override
    public void check(Site site) {
        if (site.perEnvironment()) {
            throw new IllegalStateException(("The site '%s' has content: %s and environments. The governance "
                    + "site is one tree; set per-environment: false.").formatted(site.id(), SOURCE_ID));
        }
        if (!upstream.isConfigured()) {
            throw new IllegalStateException(("The site '%s' has content: %s and no Governance Service is "
                    + "configured. Configure jeap.doc.governance.url and jeap.doc.governance.client-registration, "
                    + "or give the site another content.").formatted(site.id(), SOURCE_ID));
        }
    }
}
