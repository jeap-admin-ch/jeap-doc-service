package ch.admin.bit.jeap.doc.domain.upload.validation;

import java.util.HashSet;
import java.util.Set;

/**
 * What a Markdown set's assets may do beyond what its template allows. A page lies directly in its chapter;
 * an asset may lie in folders inside it.
 */
public final class MarkdownAssetRules {

    /** How many folders below its chapter an asset may lie. A rule of this service, not of an instance. */
    public static final int MAX_FOLDER_DEPTH = 5;

    /**
     * What no instance may add as an asset type: a page type, a document the site would render on its own
     * origin, code the site or the browser would run, and the executables a microsite refuses by default.
     */
    public static final Set<String> NEVER_ALLOWED = neverAllowed();

    private MarkdownAssetRules() {
    }

    private static Set<String> neverAllowed() {
        Set<String> extensions = new HashSet<>(Set.of(
                "md", "mdx",
                "html", "htm", "xhtml", "shtml",
                "js", "mjs", "cjs", "jsx", "ts", "tsx", "css"));
        // The constant, not the configurable refused-extensions: no configuration reaches past this list.
        extensions.addAll(MicrositeRules.REFUSED_BY_DEFAULT);
        return Set.copyOf(extensions);
    }
}
