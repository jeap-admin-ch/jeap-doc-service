package ch.admin.bit.jeap.doc.domain;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * When the architecture repository is imported, and how long one import may take.
 * <p>
 * The upstream itself is configured in {@code jeap.doc.archrepo.environments}, which belongs to the adapter
 * that calls it. What is here is the schedule and the budget, which is the domain's business.
 */
@Data
@ConfigurationProperties("jeap.doc.archrepo.import")
public class ArchitectureImportProperties {

    /**
     * When the architecture repository is imported, as a cron expression in the time zone of the service.
     * <b>An empty value means never</b> - the same convention as a site's publication schedule, and there is no
     * separate enabled flag.
     * <p>
     * The default runs <b>once an hour</b> through the working day, at a quarter to, which puts a fresh import
     * in front of every publication of a site on its default schedule - five past the hour - with twenty
     * minutes in between. That is twice the {@link #timeout} of the model step, which runs first and is what
     * the pages are generated from. A run whose artifact steps are still going at five
     * past costs nothing: a build generates from what is stored, and what this run adds is published by the
     * next one.
     * <p>
     * Hourly rather than more often because the sites are published hourly: three imports out of four produce
     * nothing a reader can see, and each one is a full fetch of every system of every environment - the
     * content hash saves the <b>write</b>, never the read.
     */
    private String cron = "0 45 5-19 * * *";

    /**
     * Whether an environment that has never been imported is imported once while the service starts. It is what
     * makes the first build after a deployment find a model.
     */
    private boolean onStartup = true;

    /**
     * How long one step of one environment may spend fetching. It is a deadline checked between items and not
     * an interrupt: the work is a sequence of independent requests, and cutting one in half buys nothing that
     * stopping cleanly after it does not.
     * <p>
     * It has to leave room for one item's retries, which is three times the client's read timeout.
     * <p>
     * <b>Ten minutes, because a step that runs out of time achieves nothing.</b> The model is all or nothing -
     * a run that cannot read every system writes nothing at all - so a deadline that a growing landscape
     * outgrows does not degrade the import, it stops it: the age gauge climbs and the same run truncates every
     * hour. The cost of the larger budget is only that a landscape genuinely unreachable is given longer
     * before the run is abandoned, and the whole time it is abandoned the stored landscape goes on being
     * generated from. It has to stay below {@link #lockLease}, which is checked while the service starts.
     */
    private Duration timeout = Duration.ofMinutes(10);

    /**
     * How long the lock of an import survives an instance that dies holding it. <b>Not a work budget</b>: the
     * lock is kept alive while the work runs, so this says how long an environment stays blocked after a
     * container is killed.
     */
    private Duration lockLease = Duration.ofMinutes(15);

    /**
     * How old the model of an environment may be before a build says so while generating from it.
     */
    private Duration staleAfter = Duration.ofHours(2);

    /**
     * The largest artifact that is replicated. One bigger than this is left where it is, with a warning naming
     * it, rather than stored - a runaway generated specification is not worth a row nobody can render.
     * <p>
     * It bounds what one answer may cost in <b>memory</b> as well as what is stored: nothing past it is ever
     * read off the wire, so an upstream offering a specification of a gigabyte is refused rather than believed
     * and only then measured.
     * <p>
     * It bounds a replicated message schema too, over the answer that carries it. Those go into the same kind
     * of unbounded column and are read whole, per system, by a build - so the argument for capping them is the
     * argument for capping an artifact, and a second property to keep in step would not be one.
     */
    private DataSize maxArtifactSize = DataSize.ofMegabytes(8);

    // A configuration error should stop the deployment, not the first import an hour later.
    @PostConstruct
    void check() {
        if (maxArtifactSize == null || maxArtifactSize.toBytes() < 1) {
            throw new IllegalStateException(
                    "jeap.doc.archrepo.import.max-artifact-size is " + maxArtifactSize + ". It is the size an "
                    + "artifact may have, and nothing is smaller than one byte - a value of zero would skip "
                    + "every artifact and every message schema of every environment, quietly.");
        }
    }
}
