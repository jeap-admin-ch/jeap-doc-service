package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

/**
 * What a documentation set documents: a system, or one component or library of it.
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
        if (site == null || system == null || kind == null) {
            throw new IllegalArgumentException("A subject names a site, a kind and a system.");
        }
        if (kind == SubjectKind.SYSTEM ? name != null : name == null) {
            throw new IllegalArgumentException(
                    "A system has no name of its own, and a component or library has one: " + kind);
        }
    }

    /** The slug this subject is documented under: its own name, or the system's where it is a system. */
    public String slug() {
        return name == null ? system : name;
    }
}
