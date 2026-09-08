package ch.admin.bit.jeap.doc.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One ask to publish a <b>whole</b> site: the identifier every part of it carries, and when it was asked for.
 * <p>
 * <b>It exists so that a full publication is a thing the service can name.</b> Without it there is no answer to
 * <i>how long did publishing this documentation take</i>: the build rows say what each part cost, a pass says
 * what one instance got through, and the wall clock of the whole - which is what an operator waits for and what
 * a capacity decision is made on - is somewhere between two instances and no single one of them has it. With an
 * id on every part of the ask, it is {@code max(finished) - min(requested)} over that id: exact, across the
 * instances, and it survives a restart because it is in the database.
 * <p>
 * Only a trigger that asks for every part of a site mints one - a full build by hand, and the architecture
 * import. An upload asks for one part, and one part is not a publication.
 *
 * @param id          the identifier, unique across sites and instances
 * @param requestedAt when the ask was made. It is the <b>same instant for every part of it</b>, and that is the
 *                    point: the parts are requested one statement at a time, so their own request instants are
 *                    microseconds apart and the last one would not say when the publication began
 */
public record Publication(String id, Instant requestedAt) {

    /** A publication asked for now. */
    public static Publication askedAt(Instant requestedAt) {
        return new Publication(UUID.randomUUID().toString(), requestedAt);
    }
}
