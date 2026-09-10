package ch.admin.bit.jeap.doc.upstream;

import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * How a request to an upstream is retried.
 * <p>
 * Spring's own retry support, which the framework brings; there is no separate retry library here. The calls go
 * through an HTTP interface proxy rather than a Spring AOP proxy, so an annotation on them would never be
 * honoured, and the parameters come from the configuration rather than from annotation attributes.
 * <p>
 * Retrying is safe here only because every request this adapter makes is a {@code GET} with no body. There is
 * no stream to replay, which is what makes retrying an arbitrary HTTP call dangerous.
 */
public final class UpstreamRetries {

    private UpstreamRetries() {
    }

    public static RetryTemplate of(UpstreamClientSettings settings) {
        return new RetryTemplate(RetryPolicy.builder()
                // A connection that failed or a read that timed out, and the statuses that say the upstream is
                // failing rather than that the answer is no.
                .includes(UpstreamException.Retryable.class, ResourceAccessException.class)
                .maxRetries(settings.getRetries())
                .delay(settings.getRetryDelay())
                .multiplier(2)
                // Instances whose schedules fire in the same second must not retry in lockstep against an
                // upstream that is already struggling.
                .jitter(settings.getRetryJitter())
                .maxDelay(settings.getMaxRetryDelay())
                .build());
    }
}
