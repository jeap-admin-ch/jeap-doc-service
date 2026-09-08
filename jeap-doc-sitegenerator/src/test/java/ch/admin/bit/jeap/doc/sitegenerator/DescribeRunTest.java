package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the run writes beside the site it produced, at the seam between the generator and the upload.
 * <p>
 * It is the only way the page describing the documentation can carry the numbers of the build that wrote it,
 * so two things matter: that the file says what a browser can read, and that a failure to write it never costs
 * a site that is otherwise ready to publish.
 */
class DescribeRunTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant GENERATED_AT = Instant.parse("2026-09-03T07:30:00Z");

    @TempDir
    Path output;

    private final DocusaurusSiteBuilder builder = new DocusaurusSiteBuilder(new BuildProperties(), null, null,
            null, null);

    @Test
    void describeRun_thenTheNumbersLieBesideTheSiteAsJson() throws IOException {
        builder.describeRun(built(), status());

        JsonNode written = JSON.readTree(
                Files.readString(output.resolve(AboutThisDocumentation.STATUS_FILE)));
        assertThat(written.get("buildId").asLong()).isEqualTo(4711L);
        assertThat(written.get("pageCount").asInt()).isEqualTo(412);
        assertThat(written.get("sizeInBytes").asLong()).isEqualTo(184_320L);
        assertThat(written.get("generatedInMillis").asLong()).isEqualTo(92_000L);
        assertThat(written.get("generatorMillis").asLong()).isEqualTo(62_000L);
        assertThat(written.propertyNames())
                .describedAs("the per-build memory peak is gone; what the container does is a series")
                .doesNotContain("memoryPeakBytes", "memoryLimitBytes", "memoryPeakExact");
    }

    /** Read by a browser, so the moment is text a browser parses rather than a count of milliseconds. */
    @Test
    void describeRun_thenTheMomentIsWrittenAsText() throws IOException {
        builder.describeRun(built(), status());

        assertThat(Files.readString(output.resolve(AboutThisDocumentation.STATUS_FILE)))
                .contains("2026-09-03T07:30:00Z");
    }

    /**
     * A site published without its numbers is a page with one table missing. A build failed over them is no
     * site at all - so this is the one thing at the seam that may not throw.
     */
    @Test
    void describeRun_whenTheFileCannotBeWritten_thenTheBuildIsNotLost() {
        BuiltSite intoNowhere = new BuiltSite(output.resolve("a-directory-that-is-not-there"), 1, 1, 1,
                Map.of());

        assertThatCode(() -> builder.describeRun(intoNowhere, status())).doesNotThrowAnyException();
    }

    private BuiltSite built() {
        return new BuiltSite(output, 412, 184_320L, 62_000L, Map.of("prod", 2));
    }

    private DocumentationStatus status() {
        return DocumentationStatus.of(4711L, GENERATED_AT, 92_000L, built());
    }
}
