package ch.admin.bit.jeap.doc.web.api;

/**
 * The semantic roles the doc service authorizes against. The system a role is granted for is carried in the
 * tenant part of the role, so a pipeline holding {@code <system-name>_%wvs_@docs_#write} may change the
 * documentation of the system {@code wvs} and of no other system.
 */
public final class Roles {

    public static final String RESOURCE_DOCS = "docs";
    public static final String OPERATION_READ = "read";
    public static final String OPERATION_WRITE = "write";

    /**
     * Write access to the documentation of the system named by the {@code system} parameter of the request.
     */
    public static final String HAS_DOCS_WRITE_ROLE_FOR_SYSTEM = "hasRole(#system, '" + RESOURCE_DOCS + "', '" + OPERATION_WRITE + "')";

    /**
     * Read access to the doc service's API, independent of a single system.
     */
    public static final String HAS_DOCS_READ_ROLE = "hasRole('" + RESOURCE_DOCS + "', '" + OPERATION_READ + "')";

    private Roles() {
    }
}
