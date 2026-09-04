package ch.admin.bit.jeap.doc.objectstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a published file is served as.
 * <p>
 * The answers come from Spring's table rather than from a list kept here, so these are the assertions that say
 * the table still covers what a documentation build emits - a browser refuses a module script it is handed as
 * bytes, and that failure would show up as a blank page rather than as an error.
 */
class MediaTypesTest {

    @ParameterizedTest
    @CsvSource({
            "index.html,        text/html;charset=UTF-8",
            "styles.css,        text/css;charset=UTF-8",
            "main.js,           text/javascript;charset=UTF-8",
            "runtime.mjs,       text/javascript;charset=UTF-8",
            "search-index.json, application/json;charset=UTF-8",
            "sitemap.xml,       application/xml;charset=UTF-8",
            "robots.txt,        text/plain;charset=UTF-8",
            // SVG is XML, so it carries the charset like the other text types.
            "logo.svg,          image/svg+xml;charset=UTF-8",
            "favicon.ico,       image/x-icon",
            "diagram.png,       image/png",
            "font.woff2,        font/woff2",
            "engine.wasm,       application/wasm"})
    void of_thenTheTypeIsWhatABrowserNeedsToRenderIt(String fileName, String expected) {
        assertThat(MediaTypes.of(fileName)).isEqualTo(expected);
    }

    /**
     * The two Spring's table has no entry for, and which a documentation build does emit.
     */
    @ParameterizedTest
    @CsvSource({
            "main.js.map,      application/json;charset=UTF-8",
            "architecture.md,  text/markdown;charset=UTF-8"})
    void of_whenSpringHasNoEntry_thenTheOnesKeptHereAnswer(String fileName, String expected) {
        assertThat(MediaTypes.of(fileName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"LICENSE", "archive.zzz", "noextension"})
    void of_whenNothingIsKnownAboutIt_thenItIsBytes(String fileName) {
        assertThat(MediaTypes.of(fileName)).isEqualTo("application/octet-stream");
    }

    @Test
    void of_thenTheExtensionIsReadWithoutRegardToCase() {
        assertThat(MediaTypes.of("INDEX.HTML")).isEqualTo(MediaTypes.of("index.html"));
        assertThat(MediaTypes.of("BUNDLE.JS.MAP")).isEqualTo(MediaTypes.of("bundle.js.map"));
    }
}
