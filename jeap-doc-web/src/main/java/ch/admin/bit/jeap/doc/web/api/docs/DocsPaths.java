package ch.admin.bit.jeap.doc.web.api.docs;

/**
 * The paths the documentation itself is administered at, as opposed to the uploads it arrived as.
 * <p>
 * A set outlives every upload that wrote it, so it is not addressed below {@code /api/uploads}. The
 * {@value #CUSTOM} segment says which documentation is meant: the documentation a team uploaded. The generated
 * documentation is not addressable at all - nothing uploaded it, and nothing can remove it.
 */
public final class DocsPaths {

    /** The family: the documentation this service holds. */
    public static final String DOCS = "/api/docs";

    /** The documentation a team wrote and uploaded. */
    public static final String CUSTOM = DOCS + "/custom";

    /** One documentation set of one subject. */
    public static final String SETS = CUSTOM + "/sets";

    /** Everything documented for one system, component or library. */
    public static final String SUBJECTS = CUSTOM + "/subjects";

    /**
     * Everything documented for one system: its own documentation and that of all its components and
     * libraries.
     * <p>
     * Its own resource rather than a parameter on {@link #SUBJECTS}, because it is not a subject: a system as
     * a subject is the system's own documentation, and this is that plus everything below it. The two need
     * different roles for the same reason.
     */
    public static final String SYSTEMS = CUSTOM + "/systems";

    private DocsPaths() {
    }
}
