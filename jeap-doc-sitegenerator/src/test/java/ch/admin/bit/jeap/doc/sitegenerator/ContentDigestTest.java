package ch.admin.bit.jeap.doc.sitegenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What decides whether the site generator runs at all.
 * <p>
 * Two runs over documentation nobody changed have to hash alike or nothing is ever skipped, and two runs over
 * documentation that differs have to hash apart or a change is never published. Both directions are asserted
 * here, because neither shows up as a failure: the first costs an hour of builds, the second publishes the
 * previous content for ever.
 */
class ContentDigestTest {

    private static final String VERSION = "1.4.0";

    /** A run's own timestamps, in the form the pages carry them. */
    private static final Set<String> TIMESTAMPS = Set.of("2026-09-07T05:05:00Z", "2026-09-07 07:05:00");

    @TempDir
    Path content;

    /**
     * The content of a part is not all text: the logo and the favicon of a site are copied into it, and an
     * image may ship beside an uploaded page. A digest that reads every file as UTF-8 fails the build of that
     * site, permanently and on every part.
     */
    @Test
    void aBinaryFileInTheContent_thenItIsHashedRatherThanFailingTheBuild() throws IOException {
        write("static/branding/logo.png", pngBytes(0x0A));
        write("prod/index.md", "# The documentation\n");

        String digest = ContentDigest.of(content, TIMESTAMPS, VERSION);

        assertThat(digest).isNotBlank();
    }

    /**
     * Two images that differ have to hash apart. A decoder that replaces what it cannot read - which is what
     * one does unless told otherwise - maps every unreadable byte onto the same replacement character, so two
     * different logos would hash alike and a changed logo would never be published.
     */
    @Test
    void twoBinaryFilesThatDiffer_thenTheDigestsDiffer() throws IOException {
        write("static/branding/logo.png", pngBytes(0x0A));
        String first = ContentDigest.of(content, TIMESTAMPS, VERSION);

        write("static/branding/logo.png", pngBytes(0x0B));
        String second = ContentDigest.of(content, TIMESTAMPS, VERSION);

        assertThat(second).isNotEqualTo(first);
    }

    /**
     * The whole point: a run whose only difference is its own clock is a run that changed nothing.
     */
    @Test
    void onlyTheRunsOwnTimestampsDiffer_thenTheDigestIsTheSame() throws IOException {
        write("prod/index.md", "---\ndate: 2026-09-07T05:05:00Z\n---\nBuilt 2026-09-07 07:05:00.\n");
        String first = ContentDigest.of(content, TIMESTAMPS, VERSION);

        write("prod/index.md", "---\ndate: 2026-09-08T05:05:00Z\n---\nBuilt 2026-09-08 07:05:00.\n");
        String second = ContentDigest.of(content,
                Set.of("2026-09-08T05:05:00Z", "2026-09-08 07:05:00"), VERSION);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void theContentChanged_thenTheDigestChanges() throws IOException {
        write("prod/index.md", "# The documentation\n");
        String first = ContentDigest.of(content, TIMESTAMPS, VERSION);

        write("prod/index.md", "# The documentation, revised\n");

        assertThat(ContentDigest.of(content, TIMESTAMPS, VERSION)).isNotEqualTo(first);
    }

    /** A page that moved is a site that changed, even where every byte of it is where it was. */
    @Test
    void aPageMoved_thenTheDigestChanges() throws IOException {
        write("prod/systems/orders/index.md", "# Orders\n");
        String first = ContentDigest.of(content, TIMESTAMPS, VERSION);

        Files.delete(content.resolve("prod/systems/orders/index.md"));
        write("prod/systems/billing/index.md", "# Orders\n");

        assertThat(ContentDigest.of(content, TIMESTAMPS, VERSION)).isNotEqualTo(first);
    }

    /** A new version of the doc service may write a page differently, so it rebuilds every part. */
    @Test
    void theGeneratorVersionChanged_thenTheDigestChanges() throws IOException {
        write("prod/index.md", "# The documentation\n");

        assertThat(ContentDigest.of(content, TIMESTAMPS, "1.5.0"))
                .isNotEqualTo(ContentDigest.of(content, TIMESTAMPS, VERSION));
    }

    @Test
    void nothingWasWritten_thenThereIsStillADigest() {
        assertThat(ContentDigest.of(content.resolve("nothing-here"), TIMESTAMPS, VERSION)).isNotBlank();
    }

    private void write(String path, String text) throws IOException {
        write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void write(String path, byte[] bytes) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** A PNG header and a byte pair that is not valid UTF-8 - the shape of what breaks a text read. */
    private static byte[] pngBytes(int last) {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) 0xFF, (byte) 0xFE,
                (byte) last};
    }
}
