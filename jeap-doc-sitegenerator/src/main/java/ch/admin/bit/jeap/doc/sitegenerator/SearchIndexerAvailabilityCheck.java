package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks while the service starts that it could index the documentation at all.
 * <p>
 * The indexer needs a Pagefind binary, and that binary arrives with {@code node_modules} - which the image
 * build installs and this service does not. So an instance whose image was built before the search was added
 * has no indexer, and the failure would otherwise be a schedule that quietly does nothing until somebody
 * noticed the site had no search.
 * <p>
 * <b>{@code jeap.doc.search.enabled: false} skips it</b>, and that is what it is for: it is how a service
 * version reaches an instance whose image is older, without failing its startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexerAvailabilityCheck implements InitializingBean {

    /** Where the Node API lives inside the installed dependencies. */
    static final String PAGEFIND_ENTRY = "pagefind/lib/index.js";

    private final BuildProperties buildProperties;
    private final SearchProperties searchProperties;

    @Override
    public void afterPropertiesSet() {
        if (!searchProperties.isEnabled()) {
            log.info("The documentation is not indexed for search on this instance, so the indexer is not "
                     + "checked for. Switch jeap.doc.search.enabled on once the image carries pagefind.");
            return;
        }
        // The settings, before anything else: a retention of one is a reader's 404 the next time a site is
        // published, and it should stop a deployment rather than surface as a support request.
        searchProperties.check();
        Path nodeModules = buildProperties.getNodeModulesDirectory();
        if (nodeModules == null) {
            // The site generator's own check says this better, and it runs too; saying nothing here is what
            // keeps one misconfiguration to one message.
            return;
        }
        Path entry = nodeModules.resolve(PAGEFIND_ENTRY);
        if (!Files.isRegularFile(entry)) {
            throw new IllegalStateException(("The search indexer is missing: %s is not there. It is installed "
                                             + "with the rest of the site template's dependencies, so this "
                                             + "instance's image was built from a package-lock.json that "
                                             + "predates the search. Rebuild the image, or set "
                                             + "jeap.doc.search.enabled to false until you have.")
                    .formatted(entry));
        }
        log.info("The search indexer is available; the documentation is indexed at the end of every build "
                 + "pass that published a part of a site.");
    }
}
