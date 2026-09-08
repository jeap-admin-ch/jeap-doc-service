package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.SitePart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which links leave a part's own broken-link check, and which stay inside it.
 * <p>
 * Both mistakes are expensive and neither is visible here without a test. A link that should have been
 * rewritten fails the whole Docusaurus build, minutes into a run; one that should not have been leaves the
 * check that catches a generator bug, and publishes a dead link instead.
 */
class CrossPartLinksTest {

    private static final String SITE = "docs";
    private static final String BASE_URL = "https://example.ch/docs/";

    /** The main environment: its paths carry no prefix within the site. */
    private static final Map<String, CrossPartLinks.EnvironmentLinks> MAIN =
            Map.of("prod", new CrossPartLinks.EnvironmentLinks("", BASE_URL));

    /** A second environment: its paths carry its id, and a page of it writes links without that id. */
    private static final Map<String, CrossPartLinks.EnvironmentLinks> DEV =
            Map.of("dev", new CrossPartLinks.EnvironmentLinks("/dev", BASE_URL + "dev/"));

    @TempDir
    Path content;

    private static SitePart systemPart(String slug, List<String> prefixes) {
        return new SitePart(PartKey.of(SITE, "system-" + slug), "the system " + slug, "systems/" + slug, true,
                List.of("prod", "dev"), prefixes);
    }

    private static SitePart shell() {
        return new SitePart(PartKey.shellOf(SITE), "the site itself", "", false, List.of("prod", "dev"),
                List.of());
    }

    private static final SitePart ORDERS = systemPart("orders", List.of("/systems/orders/",
            "/dev/systems/orders/"));
    private static final SitePart BILLING = systemPart("billing", List.of("/systems/billing/",
            "/dev/systems/billing/"));

    @Test
    void aLinkWithinTheOwnPart_thenItStaysChecked() throws IOException {
        write("prod/systems/orders/index.md", "See [the components](/systems/orders/components/).");

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isZero();
        assertThat(read("prod/systems/orders/index.md")).contains("](/systems/orders/components/)");
    }

    @Test
    void aLinkIntoAnotherPart_thenItLeavesTheCheck() throws IOException {
        write("prod/systems/orders/index.md", "See [billing](/systems/billing/).");

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isOne();
        assertThat(read("prod/systems/orders/index.md"))
                .contains("](pathname://https://example.ch/docs/systems/billing/)");
    }

    /**
     * The case the pass got wrong while every part happened to carry every environment: a page writes its
     * links relative to its own environment, and a part owns paths of the <b>site</b>. Compared without the
     * environment's prefix, a part's own subtree reads as somebody else's.
     */
    @Test
    void aPartOfANonMainEnvironment_thenItsOwnSubtreeStaysChecked() throws IOException {
        SitePart ordersOnDev = new SitePart(PartKey.of(SITE, "system-orders"), "the system orders",
                "systems/orders", true, List.of("dev"), List.of("/dev/systems/orders/"));
        write("dev/systems/orders/index.md", "See [the components](/systems/orders/components/).");

        int rewritten = CrossPartLinks.rewrite(content, ordersOnDev, List.of(shell()), DEV);

        assertThat(rewritten).isZero();
        assertThat(read("dev/systems/orders/index.md")).contains("](/systems/orders/components/)");
    }

    @Test
    void aLinkOfANonMainEnvironmentThatLeaves_thenItCarriesTheEnvironmentsPrefix() throws IOException {
        write("dev/systems/orders/index.md", "See [billing](/systems/billing/).");

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), DEV);

        assertThat(rewritten).isOne();
        assertThat(read("dev/systems/orders/index.md"))
                .contains("](pathname://https://example.ch/docs/dev/systems/billing/)");
    }

    /**
     * An image is not a route. It is published for the whole site, and a {@code pathname://} in a {@code src}
     * is a broken image rather than a link that left the check.
     */
    @Test
    void anImage_thenItIsLeftAlone() throws IOException {
        write("prod/systems/orders/index.md", "![The logo](/img/logo.png)");

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isZero();
        assertThat(read("prod/systems/orders/index.md")).isEqualTo("![The logo](/img/logo.png)");
    }

    /**
     * A diagram is a fenced block in another language whose links are absolute already, and whose brackets are
     * not Markdown at all. Rewriting inside it corrupts the diagram.
     */
    @Test
    void insideAFence_thenItIsLeftAlone() throws IOException {
        write("prod/systems/orders/index.md", """
                ```plantuml
                [Orders] --> [Billing]
                url of [Billing] is [[/systems/billing/]]
                ```
                And a [link](/systems/billing/) outside it.
                """);

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isOne();
        assertThat(read("prod/systems/orders/index.md"))
                .contains("url of [Billing] is [[/systems/billing/]]")
                .contains("And a [link](pathname://https://example.ch/docs/systems/billing/) outside it.");
    }

    @Test
    void aTildeFenceAndABacktickRunInsideIt_thenTheBlockEndsWhereItSays() throws IOException {
        write("prod/systems/orders/index.md", """
                ~~~markdown
                ```
                [inside](/systems/billing/)
                ```
                ~~~
                [outside](/systems/billing/)
                """);

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isOne();
        assertThat(read("prod/systems/orders/index.md"))
                .contains("[inside](/systems/billing/)")
                .contains("[outside](pathname://https://example.ch/docs/systems/billing/)");
    }

    @Test
    void aTitledLink_thenItIsRewrittenAndKeepsItsTitle() throws IOException {
        write("prod/systems/orders/index.md", "See [billing](/systems/billing/ \"The billing system\").");

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isOne();
        assertThat(read("prod/systems/orders/index.md")).contains(
                "](pathname://https://example.ch/docs/systems/billing/ \"The billing system\")");
    }

    @Test
    void anAngleBracketDestination_thenItIsRewritten() throws IOException {
        write("prod/systems/orders/index.md", "See [billing](</systems/billing/>).");

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isOne();
        assertThat(read("prod/systems/orders/index.md"))
                .contains("](pathname://https://example.ch/docs/systems/billing/)");
    }

    @Test
    void aLinkReferenceDefinition_thenItIsRewritten() throws IOException {
        write("prod/systems/orders/index.md", """
                See [billing][b].

                [b]: /systems/billing/ "The billing system"
                """);

        int rewritten = CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN);

        assertThat(rewritten).isOne();
        assertThat(read("prod/systems/orders/index.md")).contains(
                "[b]: pathname://https://example.ch/docs/systems/billing/ \"The billing system\"");
    }

    /**
     * The shell owns what no other part claims, so a link to its own pages stays inside the check that catches
     * a generator bug - while a link into a system's part does not.
     */
    @Test
    void theShell_thenOnlyWhatAnotherPartClaimsLeavesTheCheck() throws IOException {
        write("prod/index.md", "The [systems](/systems/) of it, and [orders](/systems/orders/).");

        int rewritten = CrossPartLinks.rewrite(content, shell(), List.of(ORDERS, BILLING), MAIN);

        assertThat(rewritten).isOne();
        assertThat(read("prod/index.md"))
                .contains("The [systems](/systems/) of it")
                .contains("[orders](pathname://https://example.ch/docs/systems/orders/)");
    }

    /** A page that is in no environment's tree belongs to no environment, so nothing resolves its links. */
    @Test
    void aPageOutsideAnyEnvironmentTree_thenItIsNotTouched() throws IOException {
        write("stray.md", "See [billing](/systems/billing/).");

        assertThat(CrossPartLinks.rewrite(content, ORDERS, List.of(BILLING, shell()), MAIN)).isZero();
        assertThat(read("stray.md")).isEqualTo("See [billing](/systems/billing/).");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private String read(String path) throws IOException {
        return Files.readString(content.resolve(path), StandardCharsets.UTF_8);
    }
}
