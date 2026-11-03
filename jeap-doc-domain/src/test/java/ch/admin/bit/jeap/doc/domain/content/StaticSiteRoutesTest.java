package ch.admin.bit.jeap.doc.domain.content;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routes Docusaurus publishes the pages of a static site at: a folder's own page decided on the names as
 * written, the number prefixes taken off afterwards.
 */
class StaticSiteRoutesTest {

    @ParameterizedTest
    @CsvSource({
            "index.md, ''",
            "README.md, ''",
            "00-index.md, index",
            "intro/why.md, intro/why",
            "01-intro/02-why.md, intro/why",
            "01-guides/index.md, guides",
            "01-guides/01-guides.md, guides",
            "01-guides/guides.md, guides/guides",
            "Guides/guides.md, guides",
            "a/b/Readme.md, a/b"})
    void routeOf_thenAsDocusaurusDerivesIt(String path, String route) {
        assertThat(StaticSiteRoutes.routeOf(path)).isEqualTo(route);
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.md", "README.md", "Index.md"})
    void isTheRootPage_whenAnIndexOrReadmeAtTheRoot_thenTrue(String path) {
        assertThat(StaticSiteRoutes.isTheRootPage(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"00-index.md", "intro/index.md", "index.json", "why.md"})
    void isTheRootPage_whenAnyOtherFile_thenFalse(String path) {
        assertThat(StaticSiteRoutes.isTheRootPage(path)).isFalse();
    }
}
