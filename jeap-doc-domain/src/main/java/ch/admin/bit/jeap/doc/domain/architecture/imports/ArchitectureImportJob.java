package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Imports one environment: every registered step, in order, each under its own lock.
 * <p>
 * The model step runs first, because it decides which systems and components exist and therefore which
 * artifacts are orphans. A step that fails does not stop the next one - two kinds of artifact are independent
 * of each other and of the model.
 * <p>
 * The one thing that does stop the steps after it is <b>the instance stopping</b>. A step takes minutes and
 * every deployment falls into one, so it is asked before each step and, through the {@link Deadline}, between
 * the requests of the one running - see {@link ArchitectureImportShutdown}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectureImportJob {

    /** The prefix of the lock one step of one environment holds. */
    static final String LOCK_PREFIX = "architectureImport-";

    /**
     * Sorted so that the model runs first, whatever order the context happened to register the beans in: it is
     * what decides which systems and components exist, and therefore which artifacts are orphans.
     */
    private final List<ArchitectureImportStep> steps;
    private final ArchitectureModelUpstream upstream;
    private final ArchitectureImportRepository imports;
    private final ArchitectureImportProperties properties;
    private final ExclusiveWork exclusiveWork;
    /**
     * Whether this instance is stopping. An import asks it between two requests and gives up on the rest -
     * see {@link ArchitectureImportShutdown}.
     */
    private final ArchitectureImportShutdown shutdown;

    /** The steps of one environment, model first. */
    private List<ArchitectureImportStep> orderedSteps() {
        return steps.stream()
                .sorted(Comparator.comparing(step -> step.kind() == ArchitectureImportKind.MODEL ? 0 : 1))
                .toList();
    }

    /** The environments an architecture repository is configured for - what the schedule iterates. */
    public List<String> environments() {
        return upstream.environments().stream().sorted().toList();
    }

    /**
     * Imports one environment, whatever the state of it.
     */
    public void importEnvironment(String environment) {
        for (ArchitectureImportStep step : orderedSteps()) {
            if (giveUp(step, environment)) {
                return;
            }
            runUnderLock(step, environment);
        }
    }

    /**
     * Imports what has never been imported successfully, and leaves the rest alone. This is what the service
     * does once while it starts, so that the first build after a deployment finds a model.
     */
    public void importWhatIsMissing() {
        for (String environment : environments()) {
            for (ArchitectureImportStep step : orderedSteps()) {
                if (imports.state(environment, step.kind()).hasEverSucceeded()) {
                    continue;
                }
                if (giveUp(step, environment)) {
                    return;
                }
                log.info("The {} of the environment {} has never been imported; importing it now.",
                        step.kind(), environment);
                runUnderLock(step, environment);
            }
        }
    }

    /**
     * The schedule fires on every instance at the same moment, so without this four instances would each fetch
     * the whole landscape and then race to write it. An instance that does not get the lock does nothing at
     * all: another one is importing into the same database, and what it stores is what this instance's builds
     * will read either way.
     * <p>
     * The lock is taken before the first request and released after the last write, so that no two instances
     * are ever fetching and writing the same environment at once.
     */
    private void runUnderLock(ArchitectureImportStep step, String environment) {
        String lock = LOCK_PREFIX + environment + "-" + step.kind();
        try {
            // An empty result means another instance holds the lock, not that the step reported nothing.
            boolean ran = exclusiveWork.underLock(lock, properties.getLockLease(),
                    () -> step.run(environment, Deadline.of(properties.getTimeout(),
                            shutdown::isStopping))).isPresent();
            if (!ran) {
                log.debug("Another instance is importing the {} of the environment {}.", step.kind(),
                        environment);
            }
        } catch (RuntimeException e) {
            if (shutdown.isStopping() || Thread.currentThread().isInterrupted()) {
                // The ordinary end of an import on a deployment: the thread was interrupted before the step
                // could stop by itself, and everything it touches - the request in flight, the lock, the write
                // - fails at once. Nobody has to act on it, so it is not reported as though somebody did.
                log.info("The import of the {} of the environment {} was cut short: this instance is stopping. "
                         + "What is stored goes on being generated from, and the next schedule imports the "
                         + "rest.", step.kind(), environment);
                return;
            }
            // A step is meant to report what went wrong rather than throw, so reaching here is a defect in one
            // - but it must still not stop the steps after it, and it must never reach a build.
            log.error("The import of the {} of the environment {} failed unexpectedly. The other kinds are "
                      + "imported all the same, and what is stored goes on being generated from.",
                    step.kind(), environment, e);
        }
    }

    /**
     * Whether to stop before this step, because the instance is. The kinds of one environment run one after
     * another and each takes minutes, so a stopping instance would otherwise start a step it cannot finish -
     * and be interrupted in it.
     */
    private boolean giveUp(ArchitectureImportStep step, String environment) {
        if (!shutdown.isStopping()) {
            return false;
        }
        log.info("The import of the environment {} stops before its {}: this instance is stopping. What is "
                 + "stored goes on being generated from, and the next schedule imports the rest.",
                environment, step.kind());
        return true;
    }
}
