package ch.admin.bit.jeap.doc.domain.upload.validation;

import java.util.List;
import java.util.Set;

/**
 * The files a ZIP of a documentation folder carries that nobody wrote.
 * <p>
 * <b>Dropped before any rule runs, and never reported.</b> A pipeline that fails because macOS wrote
 * {@code .DS_Store} into a folder fails for something the author cannot see in their file manager - and the
 * first honest attempt at an upload is exactly when that must not happen.
 * <p>
 * <b>A named list and not a pattern.</b> Deliberately not <i>ignore every dotfile</i>: a hidden file that is
 * not on this list was written by somebody, and telling them about it beats dropping it in silence - see
 * {@link FindingCode#HIDDEN_NAME}.
 * <p>
 * <b>Not configurable.</b> One list, in the domain, so that the publication can drop the same files once it
 * writes an upload into a site.
 */
public final class IgnoredPaths {

    /** Whole names, matched on the last segment of a path, case-sensitively as the object store is. */
    private static final Set<String> NAMES =
            Set.of(".DS_Store", "Thumbs.db", "desktop.ini", ".gitkeep", ".gitignore");

    /** The AppleDouble sidecar macOS writes beside a file: {@code ._design.md} carries the resource fork. */
    private static final String APPLE_DOUBLE_PREFIX = "._";

    /** The resource-fork directory macOS {@code zip} adds beside the real tree, as a first segment. */
    private static final String APPLE_RESOURCE_FORK = "__MACOSX";

    /** Editor leftovers: {@code design.md~}, {@code .design.md.swp}, {@code .~lock.design.md#}. */
    private static final List<String> SUFFIXES = List.of("~", ".swp");
    private static final String LOCK_PREFIX = ".~lock.";
    private static final String LOCK_SUFFIX = "#";

    private IgnoredPaths() {
    }

    /**
     * Whether this path is one nobody wrote.
     *
     * @param path a path as it arrived, relative and separated by {@code /}
     */
    public static boolean isIgnored(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (firstSegmentOf(path).equals(APPLE_RESOURCE_FORK)) {
            return true;
        }
        String name = lastSegmentOf(path);
        if (NAMES.contains(name) || name.startsWith(APPLE_DOUBLE_PREFIX)) {
            return true;
        }
        if (name.startsWith(LOCK_PREFIX) && name.endsWith(LOCK_SUFFIX)) {
            return true;
        }
        return SUFFIXES.stream().anyMatch(suffix -> name.length() > suffix.length() && name.endsWith(suffix));
    }

    private static String firstSegmentOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private static String lastSegmentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
