package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a part owns, which is what decides where a request is served from.
 */
class SitePartTest {

    private static SitePart partOwning(String... prefixes) {
        return new SitePart(PartKey.of("default", "system-orders"), "the system orders", "systems/orders", true,
                List.of("dev", "prod"), List.of(prefixes));
    }

    @Test
    void owns_thenEverythingBelowAPrefixBelongsToThePart() {
        SitePart part = partOwning("/systems/orders/", "/dev/systems/orders/");

        assertThat(part.owns("/systems/orders/")).isTrue();
        assertThat(part.owns("/systems/orders")).describedAs("the part's own root, without its slash").isTrue();
        assertThat(part.owns("/dev/systems/orders/system-architecture/intro/")).isTrue();
    }

    /**
     * A path that only starts with the same letters is not inside the part. Without the trailing slash,
     * {@code /systems/orders-archive/} would be served out of the publication of {@code orders}.
     */
    @Test
    void owns_whenAnotherPartsNameStartsTheSame_thenItIsNotOwned() {
        SitePart part = partOwning("/systems/orders/");

        assertThat(part.owns("/systems/orders-archive/")).isFalse();
        assertThat(part.owns("/systems/other/")).isFalse();
        assertThat(part.owns("/dev/systems/orders/")).isFalse();
    }

    /** The shell owns nothing this way: it is what is left when no other part matched. */
    /** The shell carries whole environment trees; a part named after something carries a subtree of them. */
    @Test
    void aPartKnowsWhatItCarries() {
        assertThat(partOwning("/systems/orders/").tree()).isEqualTo("systems/orders");
        assertThat(partOwning("/systems/orders/").carriesWholeEnvironments()).isFalse();
        assertThat(partOwning("/systems/orders/").carries("dev")).isTrue();
        assertThat(partOwning("/systems/orders/").carries("ref")).isFalse();
    }

    @Test
    void owns_thenTheShellClaimsNothing() {
        SitePart shell = new SitePart(PartKey.shellOf("default"), "the site itself", "", false,
                List.of("dev", "prod"), List.of());

        assertThat(shell.isShell()).isTrue();
        assertThat(shell.owns("/")).isFalse();
        assertThat(shell.specificity()).isZero();
    }

    /** The most specific part wins where two could serve a path, and the length of its prefix says which. */
    @Test
    void specificity_thenItIsTheLongestPrefix() {
        assertThat(partOwning("/systems/orders/", "/dev/systems/orders/").specificity())
                .isEqualTo("/dev/systems/orders/".length());
    }

    @Test
    void thePrefixesAreCopiedAndNullIsNone() {
        List<String> given = new ArrayList<>(List.of("/systems/orders/"));
        SitePart part = new SitePart(PartKey.of("default", "system-orders"), "the system orders",
                "systems/orders", true, List.of("prod"), given);
        given.add("/systems/tariffs/");

        assertThat(part.routePrefixes()).containsExactly("/systems/orders/");
        assertThat(new SitePart(PartKey.shellOf("default"), "the site itself", null, false, null, null)
                .routePrefixes()).isEmpty();
    }

    /** Site and part read as one name, which is what a log line and a lock name need. */
    @Test
    void theKeyReadsAsOneName() {
        assertThat(partOwning().key()).hasToString("default/system-orders");
        assertThat(PartKey.shellOf("default").isShell()).isTrue();
        assertThat(PartKey.of("default", "system-orders").isShell()).isFalse();
    }
}
