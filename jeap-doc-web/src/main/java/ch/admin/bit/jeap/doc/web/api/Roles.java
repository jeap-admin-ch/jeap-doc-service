package ch.admin.bit.jeap.doc.web.api;

/**
 * The semantic roles the doc service authorizes against. Each role names the API resource it is granted on, and
 * the system a role is granted for is carried in the tenant part, so a pipeline holding
 * {@code <system-name>_%orders_@uploads_#write} may upload documentation for the system {@code orders} and for no
 * other system.
 */
public final class Roles {

    public static final String RESOURCE_UPLOADS = "uploads";
    public static final String RESOURCE_SITES = "sites";
    public static final String OPERATION_READ = "read";
    public static final String OPERATION_WRITE = "write";
    public static final String OPERATION_ADMIN = "admin";

    /**
     * The pieces the expressions are built from. They are concatenated from constants rather than written out,
     * so that every expression stays a compile-time constant - which is what an annotation needs, and why this
     * is not a method.
     */
    private static final String CHECKING = "hasRole('";
    private static final String CHECKING_FOR_THE_SYSTEM_PARAMETER = "hasRole(#system, '";
    private static final String THEN = "', '";
    private static final String DONE = "')";

    /**
     * Uploading documentation for the system named by the {@code system} parameter of the request.
     */
    public static final String HAS_UPLOADS_WRITE_ROLE_FOR_SYSTEM =
            CHECKING_FOR_THE_SYSTEM_PARAMETER + RESOURCE_UPLOADS + THEN + OPERATION_WRITE + DONE;

    /**
     * Administering the documentation sites: asking for a site to be published.
     * <p>
     * Deliberately <b>not</b> the upload role. That one is granted per system in the tenant part precisely so
     * that a pipeline can only change its own system's documentation, while a build is per site and republishes
     * the documentation of every system on it - granting it through a system's tenant would hand each pipeline a
     * lever over the whole site.
     */
    public static final String HAS_SITES_ADMIN_ROLE = CHECKING + RESOURCE_SITES + THEN + OPERATION_ADMIN + DONE;

    /**
     * Reading what the documentation generator has been doing, independent of a single system.
     */
    public static final String HAS_SITES_READ_ROLE = CHECKING + RESOURCE_SITES + THEN + OPERATION_READ + DONE;

    private Roles() {
    }
}
