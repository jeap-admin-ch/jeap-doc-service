package ch.admin.bit.jeap.doc.domain.upload.validation;

/**
 * Why one path, or one whole tree, was not accepted.
 * <p>
 * <b>These are API.</b> A doc pipeline branches on them and prints a message per code, so they are the
 * domain's and never a template's - a template that invented one would be printed by nothing. See
 * {@code docs/upload-validation.md}.
 */
public enum FindingCode {

    /** Not a relative, normalized path: an absolute one, a {@code ..}, a backslash, a control character. */
    INVALID_PATH,

    /** The same path appears more than once in the tree. */
    DUPLICATE_PATH,

    /** A file at the root of the set. It belongs to no chapter, so nothing would publish it. */
    FILE_OUTSIDE_CHAPTER,

    /** The first segment is not a chapter folder of the named template. */
    UNKNOWN_CHAPTER,

    /** A page in a folder inside a chapter, or an asset more than five folders below it. */
    NESTED_FOLDER,

    /**
     * A file name beginning with a dot. The tooling artefacts are ignored by name - see {@link IgnoredPaths} -
     * so what is left was written by somebody, and a documentation set has no business carrying it.
     */
    HIDDEN_NAME,

    /**
     * A file name beginning with an underscore. The site generator excludes those from a docs build, so the
     * upload would succeed and publish nothing.
     */
    UNPUBLISHABLE_NAME,

    /** The file's extension is not one the template accepts. */
    FORBIDDEN_EXTENSION,

    /** A document of a name the generator writes into that chapter. Two documents, one URL. */
    RESERVED_NAME,

    /**
     * Two documents of one chapter that the generator would publish at one route, because a leading number is
     * not part of a page's URL - {@code foo.md} beside {@code 1-foo.md}.
     */
    COLLIDING_NAME,

    /** The named template does not exist. Set-level. */
    UNKNOWN_TEMPLATE,

    /** The tree holds nothing that would be published. Set-level. */
    EMPTY_TREE,

    /** An HTML microsite with no {@code index.html} at its root has no page to open. Set-level. */
    MISSING_ENTRY_POINT,

    /** A path the doc service writes itself, so a set may not bring one. */
    RESERVED_PATH,

    /** The {@code location} an HTML upload is embedded at is not a chapter of the template. Set-level. */
    UNKNOWN_LOCATION
}
