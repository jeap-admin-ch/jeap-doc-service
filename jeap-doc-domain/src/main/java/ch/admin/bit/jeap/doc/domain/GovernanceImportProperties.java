package ch.admin.bit.jeap.doc.domain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When the Governance Service is imported as the safety net.
 * <p>
 * The upstream itself is configured in {@code jeap.doc.governance.url} and {@code .client-registration}, which
 * belong to the adapter that calls it - see {@code ArchitectureImportProperties} for the same split. What is
 * here is the schedule, which is the domain's business: the import also runs on the webhook call, so this
 * schedule is only the catch-up for a call that was lost.
 */
@Data
@ConfigurationProperties("jeap.doc.governance.import")
public class GovernanceImportProperties {

    /**
     * When the Governance Service is imported, as a cron expression in the time zone of this service.
     * <b>An empty value means never</b> - the same convention as a site's publication schedule, and there is
     * no separate enabled flag.
     * <p>
     * The default is the Governance Service's own reporting job's expression, ten minutes after the scoring it
     * follows up on: a webhook call is lost rarely, so the hourly-on-the-same-minutes-as-scoring cadence only
     * ever has to find what one lost call left out.
     */
    private String cron = "0 55 6-20 * * MON-FRI";
}
