package ch.admin.bit.jeap.doc.persistence;

/**
 * A governance resource without its content.
 * <p>
 * A closed interface projection rather than the entity, so that Spring Data selects these columns and only
 * these: comparing entity tags must never read the stored bodies, which is the whole reason the import is
 * cheap.
 */
interface GovernanceResourceRefView {

    String getKind();

    String getResourceKey();

    String getEtag();
}
