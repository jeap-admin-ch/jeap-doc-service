package ch.admin.bit.jeap.doc.sitegenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SiteTemplateTest {

    @TempDir
    Path workspace;

    private final SiteTemplate template = new SiteTemplate();

    @Test
    void installInto_thenTheTemplateIsThere() throws IOException {
        template.installInto(workspace);

        assertThat(workspace.resolve("package.json")).isRegularFile();
        assertThat(workspace.resolve("docusaurus.config.js")).isRegularFile();
        assertThat(workspace.resolve("sidebars.js")).isRegularFile();
        assertThat(workspace.resolve("src/css/custom.css")).isRegularFile();
        assertThat(workspace.resolve("plugins/remark-env-links/index.js")).isRegularFile();
    }

    @Test
    void installInto_thenTheFixtureContentOfTheTemplateModuleIsNotPackaged() throws IOException {
        template.installInto(workspace);

        // The module keeps a fixture content tree so that the template can be worked on with `npm start`. It is
        // excluded from the jar: the generator writes its own content, and a leftover page would be published.
        assertThat(workspace.resolve("content")).doesNotExist();
    }

    /**
     * The rule the whole build order exists for: whatever was written as content, the application that runs is
     * the template's.
     */
    @Test
    void installInto_whenGeneratedContentImpersonatesTheApplication_thenTheTemplateWins() throws IOException {
        Files.writeString(workspace.resolve("docusaurus.config.js"), "module.exports = {evil: true}");
        Files.writeString(workspace.resolve("package.json"), "{\"name\": \"not-the-template\"}");

        template.installInto(workspace);

        assertThat(Files.readString(workspace.resolve("docusaurus.config.js"), StandardCharsets.UTF_8))
                .doesNotContain("evil")
                .contains("environments.json");
        assertThat(Files.readString(workspace.resolve("package.json"), StandardCharsets.UTF_8))
                .contains("@docusaurus/core");
    }

    /**
     * An overlay alone would not be enough: it replaces what it has, so something could still <i>add</i> a file
     * the template does not ship - and a theme file is picked up and run by the site generator.
     */
    @Test
    void installInto_whenSomethingAddedAFileTheTemplateDoesNotHave_thenItIsRemoved() throws IOException {
        Files.createDirectories(workspace.resolve("src/theme"));
        Files.writeString(workspace.resolve("src/theme/Root.tsx"), "export default () => null;");
        Files.createDirectories(workspace.resolve("sneaky"));
        Files.writeString(workspace.resolve("sneaky/payload.js"), "console.log('run me')");

        template.installInto(workspace);

        assertThat(workspace.resolve("sneaky")).doesNotExist();
        // src/ belongs to the template, so it is replaced wholesale by the copy rather than merged into.
        assertThat(workspace.resolve("src/theme/Root.tsx")).doesNotExist();
    }

    @Test
    void installInto_thenTheContentDirectoryIsNeverTouched() throws IOException {
        Files.createDirectories(workspace.resolve("content/prod"));
        Files.writeString(workspace.resolve("content/prod/index.md"), "# Generated");

        template.installInto(workspace);

        assertThat(Files.readString(workspace.resolve("content/prod/index.md"))).isEqualTo("# Generated");
    }

    @Test
    void read_thenTheLockfileOfTheTemplateItself() throws IOException {
        assertThat(template.read(SiteTemplate.LOCKFILE)).contains("\"name\": \"jeap-doc-site\"");
    }

    /**
     * The names a site may configure as its colour scheme, read from the stylesheets rather than from a list -
     * which is the whole point of reading them. If a scheme is added or renamed and this fails, what needs
     * changing is the documentation and the example configuration, not this assertion.
     */
    @Test
    void colorSchemes_thenTheOnesTheTemplateShipsAreReported() throws IOException {
        assertThat(template.colorSchemes()).contains("jeap", "neutral", "high-contrast");
    }

    @Test
    void colorSchemes_thenEachOneHasAStylesheetBehindIt() throws IOException {
        for (String scheme : template.colorSchemes()) {
            assertThat(template.read(SiteTemplate.SCHEMES_DIRECTORY + "/" + scheme + ".css"))
                    .describedAs("the stylesheet of the colour scheme %s", scheme)
                    .isNotBlank();
        }
    }
}
