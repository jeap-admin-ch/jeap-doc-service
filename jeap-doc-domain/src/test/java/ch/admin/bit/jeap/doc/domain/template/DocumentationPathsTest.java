package ch.admin.bit.jeap.doc.domain.template;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a page is served, as a link written on another page.
 * <p>
 * A path built wrongly here is a dead link on every generated page at once, and the site build fails on it
 * rather than serving it - {@code onBrokenLinks} is set to throw.
 */
class DocumentationPathsTest {

    private static final StructureChapter BUILDING_BLOCK_VIEW =
            StructureChapter.numbered(5, "5-building-block-view", "Building Block View");

    @Test
    void everyPathIsRootRelativeAndEndsWithASlash() {
        assertThat(DocumentationPaths.systems()).isEqualTo("/systems/");
        assertThat(DocumentationPaths.system("orders")).isEqualTo("/systems/orders/");
        assertThat(DocumentationPaths.structure("orders", "system-architecture"))
                .isEqualTo("/systems/orders/system-architecture/");
    }

    /**
     * The folder carries the chapter number and the URL does not. A link carrying it would point at a page
     * that does not exist, because the site generator strips the prefix.
     */
    @Test
    void aChapterAppearsWithoutItsNumber() {
        assertThat(DocumentationPaths.chapter("orders", "system-architecture", BUILDING_BLOCK_VIEW))
                .isEqualTo("/systems/orders/system-architecture/building-block-view/")
                .doesNotContain("5-");
    }

    /**
     * And an unnumbered chapter appears as its folder, because there is no prefix for anyone to strip. The
     * paths below a chapter are the same either way, which is what makes a template free not to number its
     * chapters.
     */
    @Test
    void anUnnumberedChapterAppearsAsItsFolder() {
        StructureChapter decisions = StructureChapter.unnumbered("decisions", "Decisions");

        assertThat(DocumentationPaths.chapter("orders", "governance", decisions))
                .isEqualTo("/systems/orders/governance/decisions/");
        assertThat(DocumentationPaths.page("orders", "governance", decisions, "adr-0001-use-postgres"))
                .isEqualTo("/systems/orders/governance/decisions/adr-0001-use-postgres/");
    }

    @Test
    void aPageSitsInsideItsChapter() {
        assertThat(DocumentationPaths.page("orders", "system-architecture", BUILDING_BLOCK_VIEW,
                "whitebox-view"))
                .isEqualTo("/systems/orders/system-architecture/building-block-view/whitebox-view/");
    }

    @Test
    void aGroupedPageSitsInsideItsGroup() {
        assertThat(DocumentationPaths.group("orders", "system-architecture", BUILDING_BLOCK_VIEW, "events"))
                .isEqualTo("/systems/orders/system-architecture/building-block-view/events/");
        assertThat(DocumentationPaths.page("orders", "system-architecture", BUILDING_BLOCK_VIEW, "events",
                "orders-payment-accepted-event"))
                .isEqualTo("/systems/orders/system-architecture/building-block-view/events/"
                           + "orders-payment-accepted-event/");
    }

    /**
     * A component is one of the building blocks, so its page sits in the chapter that describes the
     * decomposition rather than anywhere else.
     */
    @Test
    void aComponentSitsInTheChapterThatDescribesTheDecomposition() {
        assertThat(DocumentationPaths.component("orders", "system-architecture", BUILDING_BLOCK_VIEW,
                "orders-intake"))
                .isEqualTo("/systems/orders/system-architecture/building-block-view/components/"
                           + "orders-intake/");
    }

    /**
     * A component carries the same structure one level down, under a segment of its own. Its pages hang
     * below the component's page rather than beside it, so a reader who followed a link into the subtree is
     * still inside the component.
     */
    @Test
    void aComponentsStructureHangsBelowItsOwnPage() {
        DocumentationPaths.ComponentPaths paths = componentPaths();

        assertThat(paths.componentRoot())
                .isEqualTo("/systems/orders/system-architecture/building-block-view/components/orders-intake/");
        assertThat(paths.structure()).isEqualTo(paths.componentRoot() + "component-architecture/");
    }

    /**
     * As in the system's tree, the chapter number is in the folder and not in the URL. The site generator
     * strips the prefix, so a link carrying it would point at a page that does not exist.
     */
    @Test
    void aComponentsChapterAppearsWithoutItsNumber() {
        DocumentationPaths.ComponentPaths paths = componentPaths();

        assertThat(paths.chapter(BUILDING_BLOCK_VIEW))
                .isEqualTo("/systems/orders/system-architecture/building-block-view/components/orders-intake/"
                           + "component-architecture/building-block-view/")
                .doesNotContain("5-");
    }

    @Test
    void aPageOfAComponentSitsInsideItsChapter() {
        DocumentationPaths.ComponentPaths paths = componentPaths();

        assertThat(paths.page(BUILDING_BLOCK_VIEW, "rest-api"))
                .isEqualTo("/systems/orders/system-architecture/building-block-view/components/orders-intake/"
                           + "component-architecture/building-block-view/rest-api/");
    }

    /**
     * The same template is <i>System Architecture</i> below a system and <i>Component Architecture</i>
     * below a component, and a path holds both without them colliding.
     */
    @Test
    void theStructureBelowAComponentIsNotTheOneBelowTheSystem() {
        assertThat(componentPaths().structure())
                .contains("/system-architecture/")
                .contains("/component-architecture/");
    }

    private static DocumentationPaths.ComponentPaths componentPaths() {
        return DocumentationPaths.componentPaths("orders", "system-architecture", BUILDING_BLOCK_VIEW,
                "orders-intake", "component-architecture");
    }
}
