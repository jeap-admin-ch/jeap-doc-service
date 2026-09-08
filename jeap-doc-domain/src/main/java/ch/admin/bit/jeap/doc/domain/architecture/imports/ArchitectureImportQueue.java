package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.DocDomainConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The queue every architecture import goes onto - the scheduled ones and the ones somebody asks for.
 * <p>
 * <b>One import of an environment at a time.</b> An import takes minutes and runs on the one import thread, so
 * a second ask for an environment that is already waiting would only run the same fetch again behind it. Ten
 * asks in a row are one import, the way ten build triggers are one build - and without that, hammering the
 * administration API pushes the scheduled imports off a bounded queue.
 * <p>
 * <b>Waiting and running are not the same, though.</b> An ask that joins an import which has not started has
 * lost nothing: the fetch is still to come. An ask that arrives while one is running is different - the
 * resources it is about may already have been read, which is precisely the case the endpoint exists for, an
 * operator who has just corrected something in the architecture repository. So that ask is remembered and the
 * environment is imported once more when the run it arrived during is over. Once more, not once per ask: the
 * flag is a flag, so ten asks during one import are one further import.
 * <p>
 * The queue is bounded and what it refuses is <b>reported</b> rather than dropped in silence: the schedule
 * logs a warning and comes round again, while somebody who asked over the API is told.
 */
@Slf4j
@Service
public class ArchitectureImportQueue {

    private final ArchitectureImportJob job;
    private final TaskExecutor taskExecutor;

    /**
     * The environments queued or being imported right now, so that a second ask for one is not queued - each
     * with whether its run has started and whether somebody asked again while it was running.
     */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    /**
     * The executor is named rather than "whatever {@code TaskExecutor} this context has" - an instance may add
     * starters that contribute executors of their own. Written out because the qualifier has to reach the
     * constructor <b>parameter</b>, which Lombok does not do.
     */
    public ArchitectureImportQueue(ArchitectureImportJob job,
                                   @Qualifier(DocDomainConfiguration.ARCHITECTURE_IMPORT_TASK_EXECUTOR)
                                   TaskExecutor taskExecutor) {
        this.job = job;
        this.taskExecutor = taskExecutor;
    }

    /** What became of an ask to import one environment. */
    public enum Outcome {
        /**
         * It will be imported: either it went on the queue, or an import of it is running and this ask made it
         * run once more afterwards.
         */
        QUEUED,
        /**
         * An import of that environment was queued and had not started reading, so this ask joined it rather
         * than putting the same fetch behind it.
         */
        ALREADY_ASKED_FOR,
        /**
         * Nothing was queued - the queue is full, or this instance is stopping. The next schedule imports the
         * environment.
         */
        REFUSED
    }

    /**
     * Puts an import of one environment on the queue.
     * <p>
     * An import that is queued and has not started is joined. One that is <b>running</b> is followed by
     * another, because what this ask is about may already have been read - see the note on this class.
     */
    public Outcome submit(String environment) {
        while (true) {
            InFlight waiting = inFlight.get(environment);
            if (waiting == null) {
                return startImport(environment);
            }
            if (!waiting.started) {
                return Outcome.ALREADY_ASKED_FOR;
            }
            if (askAgain(environment, waiting)) {
                return Outcome.QUEUED;
            }
            // That run ended between reading it and raising the flag. Round again: either nothing is in
            // flight and this ask starts its own import, or a follow-up run already is and this ask joins it.
        }
    }

    /**
     * Raises the ask-again flag on a running import, and reports whether it reached the run it was meant for.
     * <p>
     * <b>Under the map's own lock on the key</b>, because {@link #runAndForget} removes the entry and then
     * reads the flag: a flag raised in between would be set on an entry nothing holds any more, no further
     * import would run, and the caller would have been told one would. Only on the same run, so an ask cannot
     * land on a follow-up that has not started reading and does not need it.
     */
    private boolean askAgain(String environment, InFlight running) {
        boolean[] raised = {false};
        inFlight.computeIfPresent(environment, (id, flight) -> {
            if (flight == running) {
                flight.askedAgain = true;
                raised[0] = true;
            }
            return flight;
        });
        return raised[0];
    }

    /** Puts the first ask for an environment on the queue. */
    private Outcome startImport(String environment) {
        InFlight own = new InFlight();
        if (inFlight.putIfAbsent(environment, own) != null) {
            // Two asks arrived at once and the other one got there first. Its import has not started - it was
            // put on the queue a moment ago - so this ask joins it.
            return Outcome.ALREADY_ASKED_FOR;
        }
        try {
            taskExecutor.execute(() -> runAndForget(environment, own));
            return Outcome.QUEUED;
        } catch (TaskRejectedException e) {
            inFlight.remove(environment, own);
            // Not "the queue is full": the executor refuses a task after it has been shut down as well, which
            // is what every rolling deployment produces - and an operator told the queue was full would go
            // looking for a slow architecture repository.
            log.warn("An import of the environment {} was not queued: {}. Either the import queue is full or "
                     + "this instance is stopping; the schedule imports the environment at its next "
                     + "occurrence.", environment, e.getMessage());
            return Outcome.REFUSED;
        }
    }

    /**
     * Puts the catch-up of everything never imported on the queue, and reports whether that worked. It is not
     * per environment, so nothing collapses it - it runs once, after the service is up.
     */
    public boolean submitWhatIsMissing() {
        try {
            taskExecutor.execute(job::importWhatIsMissing);
            return true;
        } catch (TaskRejectedException e) {
            log.warn("The catch-up import was not queued: {}. Either the import queue is full or this instance "
                     + "is stopping.", e.getMessage());
            return false;
        }
    }

    private void runAndForget(String environment, InFlight own) {
        own.started = true;
        try {
            job.importEnvironment(environment);
        } finally {
            // Whatever the import did. The environment is asked for hourly, and a failure that kept the
            // environment in this map would make every later ask a no-op.
            //
            // Removed and read as one step on the key, so that an ask arriving now either reaches this run -
            // and is answered by the import below - or finds nothing in flight and starts its own. Read after
            // the remove and it could fall between the two, and be lost.
            boolean askedAgain = forget(environment, own);
            if (askedAgain) {
                // Asked for while this run was reading, so this run may not have seen what the ask was about.
                // In the finally, because a run that failed read nothing and owes that ask all the more. The
                // entry is gone by now, so this goes through submit like any other ask - and submit answers a
                // refusal rather than throwing, so it cannot mask a failure of the run.
                log.info("The environment {} was asked for again while it was being imported, so it is "
                         + "imported once more.", environment);
                submit(environment);
            }
        }
    }

    /** Takes a finished run out of the map and reports whether somebody asked for it again while it ran. */
    private boolean forget(String environment, InFlight own) {
        boolean[] askedAgain = {false};
        inFlight.compute(environment, (id, flight) -> {
            if (flight != own) {
                // A follow-up run already holds the slot; this one has nothing left to give up.
                return flight;
            }
            askedAgain[0] = own.askedAgain;
            return null;
        });
        return askedAgain[0];
    }

    /** One environment's place on the queue: whether its run has started, and whether it was asked for again. */
    private static final class InFlight {

        /** Set when the import thread picks the run up. Until then an ask has lost nothing by joining it. */
        private volatile boolean started;

        /** Set by an ask that arrived while the run was reading, which is what makes it run once more. */
        private volatile boolean askedAgain;
    }
}
