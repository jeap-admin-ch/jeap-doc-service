package ch.admin.bit.jeap.doc.domain;

import java.time.Instant;

/**
 * A pending request to publish a site.
 * <p>
 * There is at most one per site, whatever asked how often: several triggers arriving while a build is running
 * set the same flag and are therefore one request, which is what makes a burst of uploads produce exactly one
 * follow-up run.
 *
 * @param site        the site a build was asked for
 * @param requestedAt when it was first asked for since the last build claimed it
 * @param trigger     what asked first - the pair with {@code requestedAt} describes the trigger that started the wait
 */
public record BuildRequest(String site, Instant requestedAt, BuildTrigger trigger) {
}
