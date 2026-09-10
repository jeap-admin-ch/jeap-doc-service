package ch.admin.bit.jeap.doc.upstream;

import lombok.Data;

import java.time.Duration;

/**
 * What a client does when an upstream is slow or failing.
 * <p>
 * A plain settings object rather than {@code @ConfigurationProperties}: every adapter binds its own prefix -
 * {@code jeap.doc.archrepo.client}, {@code jeap.doc.reactions.client} - and hands the result here. The
 * defaults are therefore the same for every upstream without any of them sharing a property path, which would
 * mean tuning one upstream's timeout under another's name.
 */
@Data
public class UpstreamClientSettings {

    /**
     * How long the client waits for the connection.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * How long the client waits for one response. The budget of a whole import is its deadline.
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * How often a failed request is tried again, so two means three attempts in all. Only a connection
     * failure, a read timeout, a {@code 5xx} or a {@code 429} is retried.
     */
    private int retries = 2;

    /**
     * How long to wait before the first retry. Doubled for each further one.
     */
    private Duration retryDelay = Duration.ofMillis(500);

    /**
     * How much the delay is varied, so that instances whose schedules fire together do not retry in lockstep.
     */
    private Duration retryJitter = Duration.ofMillis(250);

    /**
     * The longest a retry waits, however often the delay has been doubled.
     */
    private Duration maxRetryDelay = Duration.ofSeconds(2);
}
