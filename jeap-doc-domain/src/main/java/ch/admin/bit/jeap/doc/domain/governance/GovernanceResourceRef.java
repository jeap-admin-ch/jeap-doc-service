package ch.admin.bit.jeap.doc.domain.governance;

/**
 * A system or rule resource without its content: what the comparison between what is stored and what an index
 * lists works on.
 * <p>
 * It exists so that deciding what to fetch never reads a stored body out of the database.
 *
 * @param key the system name or rule ID the resource is addressed by. Never null for {@link
 *            GovernanceResourceKind#SYSTEM} or {@link GovernanceResourceKind#RULE}
 */
public record GovernanceResourceRef(GovernanceResourceKind kind, String key, String etag) {

    /**
     * Whether the upstream is offering the same bytes as the ones already stored.
     */
    public boolean hasSameContentAs(GovernanceResourceRef stored) {
        return stored != null && etag != null && etag.equals(stored.etag());
    }
}
