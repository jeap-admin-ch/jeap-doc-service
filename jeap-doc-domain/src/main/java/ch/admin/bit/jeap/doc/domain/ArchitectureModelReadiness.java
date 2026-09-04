package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a site may be published yet.
 * <p>
 * A site that requires the architecture model and whose model has never been imported is <b>postponed, not
 * failed</b>: its request stays standing and the next poll tries again. Failing would write a failed build and
 * consume the request, and the site would then not be rebuilt until something asked again - for the sake of a
 * window that lasts from an instance starting until its first import finishes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchitectureModelReadiness {

    private final ArchitectureModelSource architectureModel;

    /**
     * The sites already reported as waiting, so that the first poll says so and the rest do not.
     * <p>
     * Concurrent because this is a singleton and nothing stops a second caller: today only the build runner's
     * tick reads it, and that is not a property the type should depend on.
     */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public boolean isReadyToBuild(Site site) {
        if (!site.architectureModelRequired()) {
            return true;
        }
        for (SiteEnvironment environment : site.environments()) {
            if (architectureModel.isConfiguredFor(environment.id())
                && architectureModel.lastSuccessfulImportAt(environment.id()).isEmpty()) {
                reportWaiting(site, environment);
                return false;
            }
        }
        reported.remove(site.id());
        return true;
    }

    private void reportWaiting(Site site, SiteEnvironment environment) {
        String message = "The documentation site {} is not published yet: the architecture model of its "
                         + "environment {} has never been imported, and the site requires it. Its request "
                         + "stays standing and the next poll tries again.";
        if (reported.add(site.id())) {
            log.info(message, site.id(), environment.id());
        } else {
            log.debug(message, site.id(), environment.id());
        }
    }
}
