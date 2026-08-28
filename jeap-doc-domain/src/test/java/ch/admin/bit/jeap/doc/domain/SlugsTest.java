package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one definition of a slug in the doc service.
 * <p>
 * Everything named by one becomes a path segment - of an object key, of a URL, or of both - and the values come
 * from outside, so both halves matter: what is let through, and that nothing hostile can be sent through it.
 */
class SlugsTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "1", "wvs", "foo-bar-scs", "a1-b2-c3", "jeap-doc-service", "0-9"})
    void isSlug_whenLowerCaseLettersDigitsAndSingleHyphens_thenYes(String value) {
        assertThat(Slugs.isSlug(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DaziT",          // upper case
            "foo_bar",        // underscore
            "foo bar",        // space
            "-leading",       // a hyphen has to sit between two parts
            "trailing-",
            "double--hyphen",
            "foo.bar",        // a dot would make a file extension out of a name
            "foo/bar",        // a separator in something that becomes a path segment
            "../etc",         // the reason the previous line matters
            "foo\nbar",
            "über"})
    void isSlug_whenAnythingElse_thenNo(String value) {
        assertThat(Slugs.isSlug(value)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void isSlug_whenThereIsNoValue_thenNo(String value) {
        assertThat(Slugs.isSlug(value)).isFalse();
    }

    /**
     * The pattern is written with possessive quantifiers on purpose. A nested repetition that can backtrack
     * turns a long hostile value into seconds of CPU on the request thread, which is a denial of service against
     * a check meant to protect the service.
     */
    @Test
    void isSlug_whenAValueBuiltToMakeItBacktrack_thenItStillAnswersAtOnce() {
        String hostile = "a-".repeat(50_000) + "!";

        long startedAt = System.nanoTime();
        boolean slug = Slugs.isSlug(hostile);

        assertThat(slug).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    }
}
