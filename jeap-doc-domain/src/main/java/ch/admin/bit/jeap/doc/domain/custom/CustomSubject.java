package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

/**
 * What a documentation set documents: a system, or one component or library of it - or a static site as a
 * whole, which names no system.
 * <p>
 * Every name here is a slug, because the upload API only accepts slugs. That is what lets a subject be
 * matched against the architecture model without folding anything: the model derives a slug for every system
 * and component, so both sides of the join are already slugs.
 *
 * @param site   the site the subject belongs to
 * @param kind   whether this is a system, a component or a library
 * @param system the system, also for a component and for a library
 * @param name   the component or the library, null for a system
 */
public record CustomSubject(String site, SubjectKind kind, String system, String name) {

    public CustomSubject {
        if (kind == SubjectKind.SITE) {
            if (site == null || system != null || name != null) {
                throw new IllegalArgumentException("A static site is a subject of its own, naming no system.");
            }
        } else {
            if (site == null || system == null || kind == null) {
                throw new IllegalArgumentException("A subject names a site, a kind and a system.");
            }
            if (kind == SubjectKind.SYSTEM ? name != null : name == null) {
                throw new IllegalArgumentException(
                        "A system has no name of its own, and a component or library has one: " + kind);
            }
        }
    }

    /** A static site as a whole. */
    public static CustomSubject ofSite(String site) {
        return new CustomSubject(site, SubjectKind.SITE, null, null);
    }

    /**
     * The slug this subject is documented under: its own name, the system's where it is a system, and the
     * site's where it is a static site.
     */
    public String slug() {
        if (kind == SubjectKind.SITE) {
            return site;
        }
        return name == null ? system : name;
    }
}
