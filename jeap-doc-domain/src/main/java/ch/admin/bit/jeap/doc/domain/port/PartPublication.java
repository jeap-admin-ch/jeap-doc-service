package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.SharedAssets;

/**
 * Where the files of one generated part go: its own prefix, and the prefix its shared files go to.
 * <p>
 * The split is what makes several parts serveable under one base URL - see {@link SharedAssets}.
 *
 * @param prefix       where this part's own files go, named after the build that produced them, so nothing that
 *                     is being read is ever written to
 * @param sharedPrefix where the files every part emits identically go, once for the whole site
 */
public record PartPublication(String prefix, String sharedPrefix) {

    /**
     * Where one file of the generated output belongs.
     *
     * @param pathWithinOutput its path below the directory the generator wrote, with forward slashes
     */
    public String prefixOf(String pathWithinOutput) {
        return isShared(pathWithinOutput) ? sharedPrefix : prefix;
    }

    /**
     * Whether one file of the generated output is the site's rather than this part's - which is what says it
     * does not count towards what this part published.
     *
     * @param pathWithinOutput its path below the directory the generator wrote, with forward slashes
     */
    public boolean isShared(String pathWithinOutput) {
        return SharedAssets.holds(pathWithinOutput);
    }
}
