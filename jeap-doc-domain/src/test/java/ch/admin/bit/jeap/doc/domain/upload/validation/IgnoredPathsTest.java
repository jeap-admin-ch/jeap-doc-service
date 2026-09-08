package ch.admin.bit.jeap.doc.domain.upload.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files a ZIP carries that nobody wrote.
 * <p>
 * They are dropped rather than reported because a pipeline that fails for a file the author cannot see in
 * their file manager fails for the wrong reason - and that is exactly what the first honest attempt at an
 * upload would hit.
 */
class IgnoredPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "1-intro/.DS_Store",
            ".DS_Store",
            "5-building-block-view/._design.md",
            "__MACOSX/1-intro/goals.md",
            "1-intro/Thumbs.db",
            "1-intro/desktop.ini",
            "9-architecture-decision-records/.gitkeep",
            ".gitignore",
            "1-intro/goals.md~",
            "1-intro/.goals.md.swp",
            "1-intro/.~lock.goals.md#"})
    void theToolingArtefactsAreIgnored(String path) {
        assertThat(IgnoredPaths.isIgnored(path)).isTrue();
    }

    /**
     * <b>Named, not a pattern.</b> A hidden file that is not on the list was written by somebody, and telling
     * them about it beats dropping it in silence - which is what {@code HIDDEN_NAME} is for.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "1-intro/goals.md",
            "1-intro/.notes.md",
            "1-intro/.env",
            "5-building-block-view/overview.png",
            "1-intro/_draft.md"})
    void anythingElseIsNotIgnored(String path) {
        assertThat(IgnoredPaths.isIgnored(path)).isFalse();
    }

    /** {@code __MACOSX} is the directory macOS zip adds beside the tree, so it counts as a first segment. */
    @Test
    void theResourceForkDirectoryCountsOnlyAsAFirstSegment() {
        assertThat(IgnoredPaths.isIgnored("__MACOSX/x.md")).isTrue();
        assertThat(IgnoredPaths.isIgnored("1-intro/__MACOSX/x.md"))
                .describedAs("a folder inside a chapter is a NESTED_FOLDER finding, not something to ignore")
                .isFalse();
    }

    /** A name that is nothing but a suffix is a name, not a leftover: `~` alone is not a backup of anything. */
    @Test
    void aNameThatIsOnlyTheSuffixIsNotIgnored() {
        assertThat(IgnoredPaths.isIgnored("1-intro/~")).isFalse();
        assertThat(IgnoredPaths.isIgnored(null)).isFalse();
        assertThat(IgnoredPaths.isIgnored("  ")).isFalse();
    }
}
