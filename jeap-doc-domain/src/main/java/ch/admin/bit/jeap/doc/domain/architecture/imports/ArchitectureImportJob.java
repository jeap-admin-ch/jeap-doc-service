package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
     * <b>The import is the trigger.</b> A landscape or an artifact that moved is documentation that is out of
     * date, and this is what publishes it minutes later rather than at some later pass - a site fed by an
     * architecture repository is deliberately left out of the reconcile schedule, so nothing else would ask.
     * <p>
     * <b>Every part of the site, and not the ones that moved.</b> Which systems a landscape changed is a
     * question this variant does not ask: a part whose content hashes to what is published is not generated,
     * so the price of not asking is the content of every part rather than a site rebuilt - and the asking
     * would be more machinery than the rebuilding.
     */
    private final DocumentationBuildTrigger buildTrigger;
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
     * Imports one environment, whatever the state of it, and asks for its documentation when the chain stored
     * anything.
     */
    public void importEnvironment(String environment) {
        boolean stored = false;
        for (ArchitectureImportStep step : orderedSteps()) {
            if (giveUp(step, environment)) {
                // What the steps before this one stored is still asked for. The rest of the chain is the next
                // schedule's, on whichever instance is left, and that one asks for what it stores itself.
                askForTheDocumentation(environment, stored);
                return;
            }
            stored |= storedAnything(runUnderLock(step, environment));
        }
        askForTheDocumentation(environment, stored);
    }

    /**
     * Imports what has never been imported successfully, and leaves the rest alone. This is what the service
     * does once while it starts, so that the first build after a deployment finds a model.
     */
    public void importWhatIsMissing() {
        for (String environment : environments()) {
            boolean stored = false;
            for (ArchitectureImportStep step : orderedSteps()) {
                if (imports.state(environment, step.kind()).hasEverSucceeded()) {
                    continue;
                }
                if (giveUp(step, environment)) {
                    askForTheDocumentation(environment, stored);
                    return;
                }
                log.info("The {} of the environment {} has never been imported; importing it now.",
                        step.kind(), environment);
                stored |= storedAnything(runUnderLock(step, environment));
            }
            askForTheDocumentation(environment, stored);
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
    private ImportOutcome runUnderLock(ArchitectureImportStep step, String environment) {
        String lock = LOCK_PREFIX + environment + "-" + step.kind();
        try {
            // An empty result means another instance holds the lock, not that the step reported nothing.
            Optional<ImportOutcome> outcome = exclusiveWork.underLock(lock, properties.getLockLease(),
                    () -> step.run(environment, Deadline.of(properties.getTimeout(),
                            shutdown::isStopping)));
            if (outcome.isEmpty()) {
                log.debug("Another instance is importing the {} of the environment {}.", step.kind(),
                        environment);
            }
            return outcome.orElse(null);
        } catch (RuntimeException e) {
            if (shutdown.isStopping() || Thread.currentThread().isInterrupted()) {
                // The ordinary end of an import on a deployment: the thread was interrupted before the step
                // could stop by itself, and everything it touches - the request in flight, the lock, the write
                // - fails at once. Nobody has to act on it, so it is not reported as though somebody did.
                log.info("The import of the {} of the environment {} was cut short: this instance is stopping. "
                         + "What is stored goes on being generated from, and the next schedule imports the "
                         + "rest.", step.kind(), environment);
                return null;
            }
            // A step is meant to report what went wrong rather than throw, so reaching here is a defect in one
            // - but it must still not stop the steps after it, and it must never reach a build.
            log.error("The import of the {} of the environment {} failed unexpectedly. The other kinds are "
                      + "imported all the same, and what is stored goes on being generated from.",
                    step.kind(), environment, e);
            return null;
        }
    }

    /**
     * Whether this step's run put something in the database that a page would read.
     * <p>
     * <b>{@code REPLACED} and nothing else</b>, which is what that outcome means: something changed and was
     * written. A null outcome is a step another instance was holding, or one that threw - neither says
     * anything was stored by this instance.
     * <p>
     * <b>{@code PARTIAL} deliberately does not count</b>, and it is worth saying why, because it is tempting:
     * an artifact step reports it whenever <i>any</i> entry of its index could not be replicated, whether or
     * not the run stored the rest. Counting it would ask for every part of the site on every run for as long
     * as one component's specification stays unfetchable - an hourly content pass over every system of the
     * landscape that publishes nothing. The ordinary recovery needs none of it: the run in which a failing
     * fetch finally succeeds stores it and reports {@code REPLACED}. What is left over is the run that stores
     * one artifact while another is still unfetchable; that one is not published until something else moves,
     * and telling the two apart needs a signal the outcome does not carry today.
     */
    private static boolean storedAnything(ImportOutcome outcome) {
        return outcome == ImportOutcome.REPLACED;
    }

    /**
     * Asks for the documentation of this environment to be published, once the whole chain has run.
     * <p>
     * <b>Here and not in the step that stored something</b>, for two reasons. A build asked for by the model
     * step would start while the artifacts and the message schemas of the same environment were still being
     * fetched, and publish the landscape beside the artifacts of the run before it. And a run in which the
     * model is unchanged while an artifact is newly replicated - a schema whose first fetch failed and whose
     * second succeeded - would ask for nothing at all, leaving the page that says the schema is missing up
     * until the landscape happened to move: a site an architecture repository feeds is left out of the
     * reconcile schedule, so nothing else asks.
     * <p>
     * <b>After every write of the chain</b>, so that a build claimed a moment later reads what this run
     * stored rather than what it replaced - the state row is what the landscape a build reads is held under.
     * <p>
     * Every part, because which of them the run changed is not asked: a part is one system, and a part whose
     * content has not moved is not generated. A failure here does not fail the import - what was stored is
     * stored, and the next import asks again.
     */
    private void askForTheDocumentation(String environment, boolean stored) {
        if (!stored) {
            return;
        }
        try {
            int parts = buildTrigger.requestBecauseTheArchitectureWasImported(environment);
            if (parts > 0) {
                log.info("The import of the environment {} asked for {} part(s) of the documentation to be "
                         + "built.", environment, parts);
            }
        } catch (RuntimeException e) {
            log.warn("The environment {} was imported, but the documentation it changes could not be asked "
                     + "for. The next import asks again.", environment, e);
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
