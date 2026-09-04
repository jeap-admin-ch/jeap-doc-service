package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The one definition of a slug in the doc service.
 * <p>
 * Everything named by one becomes a path segment - of an object key, of a URL, or of both - and the values come
 * from outside, so both halves matter: what is let through, and that nothing hostile can be sent through it.
 */
class SlugsTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "1", "orders", "foo-bar-scs", "a1-b2-c3", "jeap-doc-service", "0-9"})
    void isSlug_whenLowerCaseLettersDigitsAndSingleHyphens_thenYes(String value) {
        assertThat(Slugs.isSlug(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Catalog",        // upper case
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

    @ParameterizedTest
    @CsvSource({
            "orders,                orders",
            "ORDERS,                orders",
            "Order Fulfilment,      order-fulfilment",
            "Order  Fulfilment,     order-fulfilment",   // a run of separators is one hyphen
            "order_fulfilment,      order-fulfilment",
            "orders/payment,        orders-payment",
            "'  Orders  ',          orders",             // and so leading and trailing ones disappear
            "-orders-,              orders",
            "Zürich,                zurich",             // the letter survives, the umlaut does not
            "Café,                  cafe",
            "orders-payment-scs,    orders-payment-scs",
            "v2,                    v2"})
    void toSlug_whenANameFromOutside_thenAPathSegment(String name, String expected) {
        assertThat(Slugs.toSlug(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"***", "---", ".", "   ", "!?"})
    void toSlug_whenTheNameCarriesNoLetterAndNoDigit_thenItRefuses(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Slugs.toSlug(name))
                .withMessageContaining(name.strip().isEmpty() ? "blank" : name);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void toSlug_whenThereIsNoName_thenItRefuses(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> Slugs.toSlug(name));
    }

    /**
     * The invariant the importer relies on: it never has to check the result, because there is no name that
     * yields something {@link Slugs#isSlug} would then reject.
     */
    @Test
    void toSlug_whateverTheName_thenEitherItRefusesOrTheResultIsASlug() {
        Random random = new Random(20260828);
        for (int i = 0; i < 5_000; i++) {
            String name = randomName(random);
            try {
                assertThat(Slugs.toSlug(name)).as("the slug of '%s'", name).matches(Slugs::isSlug);
            } catch (IllegalArgumentException refused) {
                // The other half of the invariant: refusing is allowed, returning a non-slug is not.
            }
        }
    }

    /**
     * Names built from the characters that actually turn up in an architecture repository, plus the ones that
     * would break a path segment.
     */
    private static String randomName(Random random) {
        String alphabet = "abzABZ019 -_./äöüéß!?\t";
        StringBuilder name = new StringBuilder();
        for (int i = random.nextInt(12); i >= 0; i--) {
            name.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return name.toString();
    }

    /**
     * A message type name is camel-cased and is split into its words first, so {@link Slugs#toSlug} - which
     * would run them together - is not what it goes through. The awkward cases are the acronym run and the
     * version number: in {@code ERPEvent} the acronym is one word and {@code Event} starts the next, and
     * {@code V2} reads as {@code v2} rather than being glued to what came before it.
     */
    @ParameterizedTest
    @CsvSource({
            "OrdersPaymentAcceptedEvent,            orders-payment-accepted-event",
            "OrdersCheckErpAvailabilityV2Command,   orders-check-erp-availability-v2-command",
            "OrdersRefValidated,                    orders-ref-validated",
            "ERPEvent,                              erp-event",
            "Simple,                                simple",
            "A,                                     a",
            "orders_legacy_event,                   orders-legacy-event",
            "Orders-Foo-Event,                      orders-foo-event",   // the same segment as OrdersFooEvent
            "OrdersErpProcessStateChanged,          orders-erp-process-state-changed",
            "ZürichEvent,                           zurich-event",       // the letter survives the accent
            "ÄÖÜ,                                   aou",
            "OrdersFürAlle,                         orders-fur-alle"})
    void toMessageSlug_whenAMessageTypeName_thenItIsKebabCased(String name, String expected) {
        assertThat(Slugs.toMessageSlug(name)).isEqualTo(expected);
    }

    /**
     * A name that yields no path segment has no page to be written to - one made of nothing but punctuation,
     * or one written in a script that has no lower-case ASCII to become. It refuses rather than handing a
     * bracket to a URL, and the importer turns the refusal into an abandoned run naming the message.
     */
    @ParameterizedTest
    @ValueSource(strings = {"()", "..", "---", "  ", "!?", "漢字"})
    void toMessageSlug_whenTheNameYieldsNoPathSegment_thenItRefuses(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> Slugs.toMessageSlug(name));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void toMessageSlug_whenThereIsNoName_thenItRefuses(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> Slugs.toMessageSlug(name));
    }

    @Test
    void toMessageSlug_whateverTheName_thenEitherItRefusesOrTheResultIsASlug() {
        Random random = new Random(20260902);
        for (int i = 0; i < 5_000; i++) {
            String name = randomName(random);
            try {
                assertThat(Slugs.toMessageSlug(name)).as("the slug of '%s'", name).matches(Slugs::isSlug);
            } catch (IllegalArgumentException refused) {
                // Refusing is allowed, returning a non-slug is not.
            }
        }
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
