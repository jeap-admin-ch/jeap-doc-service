package ch.admin.bit.jeap.doc.domain.upload.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a published microsite is made of. It follows no structure template, so this list is the domain's and
 * not a copy per template.
 */
class MicrositeRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"html", "htm", "css", "js", "map", "json", "png", "svg", "woff2", "pdf",
            "webmanifest", "ico"})
    void theAssetsOfABuiltSiteAreAllowed(String extension) {
        assertThat(MicrositeRules.allows(extension)).isTrue();
    }

    /**
     * <b>Markdown is refused here.</b> It belongs in a Markdown upload, where the template's chapter rules
     * reach it - accepted as a microsite asset it would be published as a file nobody renders.
     */
    @ParameterizedTest
    @ValueSource(strings = {"md", "mdx", "exe", "sh", "jar", "zip"})
    void anythingThatIsNotAnAssetIsRefused(String extension) {
        assertThat(MicrositeRules.allows(extension)).isFalse();
    }

    @Test
    void aFileWithNoExtensionIsRefused() {
        assertThat(MicrositeRules.allows(null)).isFalse();
        assertThat(MicrositeRules.allows("")).isFalse();
    }

    /**
     * {@code js} is on the list deliberately: Asciidoctor output, Spring REST Docs and a Swagger UI bundle
     * all ship JavaScript. What contains it is the iframe and the content security policy of the publication.
     */
    @Test
    void javascriptIsOnTheListAndThatIsADecision() {
        assertThat(MicrositeRules.allowedFileExtensions()).contains("js");
    }

    @Test
    void theEntryPointIsIndexHtml() {
        assertThat(MicrositeRules.ENTRY_POINT).isEqualTo("index.html");
    }
}
