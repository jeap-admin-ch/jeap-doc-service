package ch.admin.bit.jeap.doc.domain.upload;

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
}
