package ch.admin.bit.jeap.doc.domain.upload.validation;

import java.util.Locale;
import java.util.Set;

/**
 * What an HTML upload may carry, and what it has to carry.
 * <p>
 * <b>A denylist rather than an allowlist</b>, because a microsite follows no template: it is published as it
 * is, and a build emits file types nobody listed in advance - a source map, a web manifest, a LICENSE with no
 * extension at all. What is refused is what has no business in documentation and every business on a
 * workstation. What contains the rest is the sandbox the microsite is served and framed with.
 */
public final class MicrositeRules {

    /** The page an iframe opens. A microsite without one has nothing to show. */
    public static final String ENTRY_POINT = "index.html";

    /**
     * The one path a microsite may not use, because the doc service writes it: the text of the set's pages,
     * extracted when it is uploaded and read back by the search index run. One line per page, tab separated -
     * the storage adapter's own format, and the extension says so.
     * <p>
     * It lies under the set's own prefix so that removing the set removes it and the sweep counts it, which
     * is what keeps every other rule about a set's storage true. The price is this name, and refusing it at
     * upload is cheaper than a set whose own file and whose index quietly overwrite each other.
     */
    public static final String SEARCH_TEXT = "_jeap-search.tsv";

    /**
     * What a microsite may not carry unless an instance configures otherwise - executables, scripts a
     * workstation runs, and the archives that install them.
     * <p>
     * Scripting sources like {@code py} and {@code rb} are deliberately not here: they are what a
     * documentation set quotes, and a browser runs none of them.
     */
    public static final Set<String> REFUSED_BY_DEFAULT = Set.of(
            "exe", "com", "cmd", "bat", "msi", "scr", "lnk", "reg",
            "ps1", "psm1", "vbs", "vbe", "wsf",
            "sh", "bash", "zsh",
            "jar", "dll", "so", "dylib",
            "app", "deb", "rpm", "apk", "pkg", "dmg");

    private MicrositeRules() {
    }

    /**
     * Whether a file of this extension belongs in a microsite. A file without one does: a build writes
     * {@code LICENSE} and {@code CNAME}, and nothing runs them.
     */
    public static boolean allows(String extension, Set<String> refused) {
        return extension == null || extension.isEmpty()
               || !refused.contains(extension.toLowerCase(Locale.ROOT));
    }
}
