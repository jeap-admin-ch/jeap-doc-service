package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * How a REST path is written when two of them are compared.
 * <p>
 * <b>The architecture repository's own rule</b>, {@code RestApi.pathWithoutVariableNames}, which is how its
 * importers resolve an observed call onto an operation. Every path variable becomes {@code {}}, so
 * {@code {bpId}} and {@code {businessPartnerId}} are one segment, and a trailing slash is dropped - the two
 * sides of a comparison come from two different parsers and spell the same operation differently.
 */
public final class RelationPaths {

    private RelationPaths() {
    }

    /** The path as both sides of a comparison spell it. A path that is {@code /} keeps its slash. */
    public static String normalised(String path) {
        if (path == null) {
            return "";
        }
        String replaced = path.strip().replaceAll("\\{[^}]*+}", "{}");
        if (!replaced.endsWith("/") || replaced.length() == 1) {
            return replaced;
        }
        return replaced.substring(0, replaced.length() - 1);
    }
}
