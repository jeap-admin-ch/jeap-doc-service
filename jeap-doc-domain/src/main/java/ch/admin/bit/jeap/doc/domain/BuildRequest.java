package ch.admin.bit.jeap.doc.domain;

import java.time.Instant;

/**
 * A pending request to publish one part of a site.
 * <p>
 * There is at most one per part, whatever asked how often: several triggers arriving while a build is running
 * set the same flag and are therefore one request, which is what makes a burst of uploads produce exactly one
 * follow-up run.
 *
 * @param part        the part a build was asked for
 * @param requestedAt when it was first asked for since the last build claimed it
 * @param trigger     what asked first - the pair with {@code requestedAt} describes the trigger that started the wait
 * @param publication the full publication this request is part of, or null where it is not part of one - an
 *                    upload asks for one part, and one part is not a publication. See {@link Publication}
 * @param forced      whether the build this leads to may be skipped by its content digest. Its own field
 *                    rather than a reading of {@code trigger}, which records what asked <b>first</b>: a
 *                    request already pending when an operator forces a publication keeps the earlier
 *                    trigger, and the reason to force one is that something outside the content changed
 */
public record BuildRequest(PartKey part, Instant requestedAt, BuildTrigger trigger, Publication publication,
                           boolean forced) {

    public String site() {
        return part.site();
    }
}
