package ch.admin.bit.jeap.doc.domain.upload;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Limits and timeouts the doc service applies to an upload.
 */
@Data
@ConfigurationProperties("jeap.doc.upload")
public class UploadProperties {

    /**
     * The size an upload may have when an instance configures none.
     */
    public static final DataSize DEFAULT_MAX_SIZE = DataSize.ofMegabytes(50);

    /**
     * The highest {@code max-paths} an instance may configure.
     * <p>
     * A hundred thousand is far more than the largest documentation set anyone has uploaded and still small
     * enough that the body bound derived from it stays a bound: at the longest path this service accepts, that
     * is a request of a few tens of megabytes.
     */
    public static final int MAX_PATHS_CEILING = 100_000;

    /**
     * Maximum size of an uploaded bundle. A larger bundle is rejected without being read.
     */
    private DataSize maxSize = DEFAULT_MAX_SIZE;

    /**
     * How long an upload may be in progress before a further attempt under the same upload id takes it over.
     * <p>
     * An upload whose service died while its bundle was streaming would otherwise block its upload id forever.
     * The timeout has to be longer than a legitimate upload of {@link #maxSize} takes, and short enough that a
     * retrying pipeline does not run out of attempts waiting for it.
     */
    private Duration inProgressTimeout = Duration.ofMinutes(2);

    /**
     * When the doc service forgets an upload it received.
     */
    private Housekeeping housekeeping = new Housekeeping();

    /** What the structure validation endpoint accepts - see {@link Validation}. */
    private Validation validation = new Validation();

    /**
     * The nightly clean-up of what the doc service received.
     * <p>
     * It removes the uploads from the database only; the bundles in the object storage are expired by a
     * lifecycle rule of the bucket, which has to be set a little longer than {@link #retention} so that an
     * upload never outlives the bundle it points at.
     */
    @Data
    public static class Housekeeping {

        /**
         * Whether the doc service removes old uploads at all.
         */
        private boolean enabled = true;

        /**
         * How long an upload is kept after it was last received. Everything older is removed, whatever state it
         * is in: what has not been generated from in two weeks is not going to be.
         */
        private Duration retention = Duration.ofDays(14);

        /**
         * When to look, in the time zone of the service - at night, when nothing is uploading.
         */
        private String cron = "0 30 2 * * *";
    }

    /**
     * The two bounds of the structure validation endpoint.
     * <p>
     * A nested class rather than a record, which is what {@link Housekeeping} beside it already is: following
     * the neighbour beats importing the shape the site generator passes into a template.
     */
    @Data
    public static class Validation {

        /**
         * The most files a documentation set may hold. Past it the request is refused rather than answered,
         * because a tree of that size is a mistake in the workflow configuration and not a documentation set.
         * <p>
         * It bounds an upload as well as the validation endpoint, so it is a limit on what a set may be. The
         * sets that exist hold twelve to seventeen pages, and a set with a page per chapter and thirty
         * screenshots is about fifty files.
         */
        private int maxPaths = 200;

        /**
         * The most findings one report carries. A report of forty problems is already unreadable, and what is
         * left out is counted rather than dropped in silence.
         */
        private int maxFindings = 50;
    }

    /**
     * A configuration error stops the deployment rather than the first validation.
     */
    @PostConstruct
    void check() {
        if (validation.getMaxPaths() < 1) {
            throw new IllegalStateException(
                    "jeap.doc.upload.validation.max-paths is " + validation.getMaxPaths()
                    + ". A documentation set has at least one file.");
        }
        if (validation.getMaxPaths() > MAX_PATHS_CEILING) {
            // The bound on the body is derived from this one, so a value far above what a documentation set
            // can be makes both bounds meaningless - and the endpoint is reachable by every pipeline.
            throw new IllegalStateException(
                    "jeap.doc.upload.validation.max-paths is " + validation.getMaxPaths()
                    + ". No documentation set has that many files, and the bound on the request body is "
                    + "derived from this one, so a value above " + MAX_PATHS_CEILING + " bounds neither.");
        }
        if (validation.getMaxFindings() < 1) {
            throw new IllegalStateException(
                    "jeap.doc.upload.validation.max-findings is " + validation.getMaxFindings()
                    + ". A report that may carry no finding could not say what is wrong.");
        }
    }
}
