package ch.admin.bit.jeap.doc.web.site;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard on the path a request becomes an object key from.
 * <p>
 * The container normalises and would already refuse what could leave the application root. This is checked here
 * as well because the key is built by concatenation, over a bucket that also holds the uploaded bundles - and a
 * rule that matters should not live only in another component. Asserted as a unit, because through MockMvc the
 * escapes are re-encoded and the two halves of the guard cannot be told apart.
 */
class SiteRequestHandlerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/../uploads/docs/1/1/bundle.zip",
            "/dev/../../index.html",
            "/..",
            "/dev/..",
            "/dev\\\\..\\\\index.html",
            "/dev/%2e%2e/index.html",
            "/index.html%00.png"})
    void isSuspicious_whenThePathCouldLeaveTheSite_thenItIsRefused(String path) {
        assertThat(SiteRequestHandler.isSuspicious(path)).isTrue();
    }

    /**
     * And the other half: a documentation site is full of dots, and none of these may be refused.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/index.html",
            "/dev/index.html",
            "/assets/js/main.abc123.js",
            "/assets/js/runtime~main.1a2b3c.js",
            "/llms-full.txt",
            "/systems/wvs/api/index.html",
            "/img/logo.svg",
            "/1-intro/index.html",
            "/uebersicht/index.html"})
    void isSuspicious_whenThePathIsAnOrdinaryPageOrAsset_thenItIsServed(String path) {
        assertThat(SiteRequestHandler.isSuspicious(path)).isFalse();
    }

    /**
     * A file whose name merely contains two dots in a row is not a traversal - but it is also not something a
     * site generator emits, so the guard is deliberately blunt and this records that choice.
     */
    @Test
    void isSuspicious_whenTwoDotsAreInsideAName_thenItIsRefusedToo() {
        assertThat(SiteRequestHandler.isSuspicious("/release..notes.html")).isTrue();
    }
}
