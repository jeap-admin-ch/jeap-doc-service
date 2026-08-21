package ch.admin.bit.jeap.doc.web.api;

/**
 * The semantic roles the doc service authorizes against. Each role names the API resource it is granted on, and
 * the system a role is granted for is carried in the tenant part, so a pipeline holding
 * {@code <system-name>_%wvs_@uploads_#write} may upload documentation for the system {@code wvs} and for no
 * other system.
 */
public final class Roles {

    public static final String RESOURCE_UPLOADS = "uploads";
    public static final String RESOURCE_DOCS = "docs";
    public static final String OPERATION_READ = "read";
    public static final String OPERATION_WRITE = "write";

    /**
     * Uploading documentation for the system named by the {@code system} parameter of the request.
     */
    public static final String HAS_UPLOADS_WRITE_ROLE_FOR_SYSTEM = "hasRole(#system, '" + RESOURCE_UPLOADS + "', '" + OPERATION_WRITE + "')";

    /**
     * Read access to the doc service's API, independent of a single system.
     */
    public static final String HAS_DOCS_READ_ROLE = "hasRole('" + RESOURCE_DOCS + "', '" + OPERATION_READ + "')";

    private Roles() {
    }
}
