package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A group is a folder directly below a chapter that holds at least one page. */
class CustomSetGroupsTest {

    private static CustomSet setOf(CustomPage... pages) {
        return new CustomSet(1L, new CustomSetKey("default", SubjectKind.SYSTEM, "orders", null,
                SourceFormat.MARKDOWN, "arc42", null, null), null, 1L, "obj", "sha", 10L,
                new CustomProvenance(null, null, null, null, null, null), List.of(pages));
    }

    private static CustomPage page(String fileName, String title) {
        return new CustomPage("1-intro", fileName, title, 1, false);
    }

    private static CustomPage asset(String fileName) {
        return new CustomPage("1-intro", fileName, null, 0, true);
    }

    @Test
    void pagesOf_excludesAPageInsideAGroup() {
        CustomSet set = setOf(page("goals.md", "Goals"), page("shipping-partners/orders.md", "Orders"));

        assertThat(set.pagesOf("1-intro")).extracting(CustomPage::fileName).containsExactly("goals.md");
    }

    @Test
    void groupsOf_findsOneGroupPerFolderHoldingAPage() {
        CustomSet set = setOf(
                page("goals.md", "Goals"),
                page("shipping-partners/index.md", "Shipping Partners"),
                page("shipping-partners/orders.md", "Orders"),
                page("hr/payroll.md", "Payroll"));

        List<PageGroup> groups = set.groupsOf("1-intro");

        assertThat(groups).extracting(PageGroup::folder).containsExactlyInAnyOrder("shipping-partners", "hr");
    }

    @Test
    void groupsOf_separatesTheLandingPageFromTheRest() {
        CustomSet set = setOf(
                page("shipping-partners/index.md", "Shipping Partners"),
                page("shipping-partners/orders.md", "Orders"),
                page("shipping-partners/invoices.md", "Invoices"));

        PageGroup group = set.groupsOf("1-intro").getFirst();

        assertThat(group.landingPage()).map(CustomPage::fileName).contains("shipping-partners/index.md");
        assertThat(group.pages()).extracting(CustomPage::fileName)
                .containsExactlyInAnyOrder("shipping-partners/orders.md", "shipping-partners/invoices.md");
        assertThat(group.label()).isEqualTo("Shipping Partners");
    }

    /** Without a landing page, the group is labelled by its own stripped folder name. */
    @Test
    void groupsOf_withNoLandingPage_isLabelledByItsFolder() {
        CustomSet set = setOf(page("02-shipping-partners/orders.md", "Orders"));

        PageGroup group = set.groupsOf("1-intro").getFirst();

        assertThat(group.landingPage()).isEmpty();
        assertThat(group.label()).isEqualTo("shipping-partners");
        assertThat(group.name()).isEqualTo("shipping-partners");
    }

    /** A folder holding only assets is not a group - it stays an asset folder, as it always was. */
    @Test
    void groupsOf_ignoresAFolderOfAssetsOnly() {
        CustomSet set = setOf(page("goals.md", "Goals"), asset("img/overview.png"));

        assertThat(set.groupsOf("1-intro")).isEmpty();
        assertThat(set.assetsOf("1-intro")).extracting(CustomPage::fileName).containsExactly("img/overview.png");
    }

    @Test
    void groupsOf_readsNoChapterButTheOneAsked() {
        CustomSet set = setOf(page("shipping-partners/orders.md", "Orders"));

        assertThat(set.groupsOf("5-building-block-view")).isEmpty();
    }

    /** {@code PageGroup.of} can also be asked directly, with the files of one folder already picked out. */
    @Test
    void pageGroupOf_withExplicitFiles_splitsTheLandingPageOut() {
        PageGroup group = PageGroup.of("shipping-partners",
                List.of(page("shipping-partners/readme.md", "Readme"), page("shipping-partners/orders.md", "Orders")));

        assertThat(group.landingPage()).map(CustomPage::fileName).contains("shipping-partners/readme.md");
        assertThat(group.pages()).hasSize(1);
    }

    @Test
    void pageGroupOf_withNoPages_hasNoLandingPageEither() {
        PageGroup group = PageGroup.of("shipping-partners", List.of());

        assertThat(group.landingPage()).isEqualTo(Optional.empty());
        assertThat(group.pages()).isEmpty();
        assertThat(group.hint()).isEqualTo(OrderHint.NONE);
    }
}
