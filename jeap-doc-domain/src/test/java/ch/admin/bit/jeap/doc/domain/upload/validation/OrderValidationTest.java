package ch.admin.bit.jeap.doc.domain.upload.validation;

import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Would a page's own placement be accepted? */
class OrderValidationTest {

    private static final StructureReport ACCEPTED = new StructureReport("arc42", 1, 0,
            List.of("1-intro"), List.of("md"), List.of(), List.of(), 0);

    private final OrderValidation validation = new OrderValidation();

    private static DocumentationPlacement markdown() {
        return new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null, null, "arc42",
                SourceFormat.MARKDOWN, null, null);
    }

    private static DocumentationPlacement staticSite() {
        return new DocumentationPlacement(DocumentationType.SITE_DOCS, null, null, null, null,
                SourceFormat.MARKDOWN, null, null);
    }

    private static DocumentationPlacement microsite() {
        return new DocumentationPlacement(DocumentationType.COMPONENT_DOCS, "orders", "orders-intake", null,
                "arc42", SourceFormat.HTML, "6-runtime-view", "reference");
    }

    private static UploadedBundles.ReceivedBundle bundleOf(Map<String, String> content) {
        return new UploadedBundles.ReceivedBundle() {
            @Override
            public List<String> paths() {
                return List.copyOf(content.keySet());
            }

            @Override
            public long declaredUnpackedSize() {
                return 0;
            }

            @Override
            public String sha256() {
                return "sha";
            }

            @Override
            public long sizeInBytes() {
                return 0;
            }

            @Override
            public byte[] head(String path, int maxBytes) {
                String text = content.get(path);
                return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public void close() {
                // nothing to release: this bundle is held in memory for the test.
            }
        };
    }

    @Test
    void check_whenNoPageNamesAPlacement_thenNothing() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\ntitle: Goals\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(),
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenASidebarPositionIsNotANumber_thenInvalidPosition() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_position: banana\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(),
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code, StructureFinding::path)
                .containsExactly(tuple(FindingCode.INVALID_POSITION, "1-intro/goals.md"));
    }

    @Test
    void check_whenASidebarPositionIsADecimal_thenNotInvalid() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_position: 2.5\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(),
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenBothDirectionsAreNamed_thenInvalidReference() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: a\nsidebar_before: b\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(),
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code)
                .containsExactly(FindingCode.INVALID_REFERENCE);
    }

    @Test
    void check_whenAReferenceIsAList_thenInvalidReference() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after:\n  - one\n  - two\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(),
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code)
                .containsExactly(FindingCode.INVALID_REFERENCE);
    }

    @Test
    void check_whenAValidReferenceIsNamed_thenNothing() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: intro\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(),
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    /** A static site has no generated siblings and no chapter of its own: a reference names nothing real. */
    @Test
    void check_whenAStaticSitePageNamesAReference_thenInvalidReferenceEvenIfWellFormed() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "intro.md", "---\nsidebar_after: intro\n---\n\n# Intro\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, staticSite(),
                List.of("intro.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code)
                .containsExactly(FindingCode.INVALID_REFERENCE);
    }

    @Test
    void check_whenAStaticSitePageNamesAPosition_thenStillAllowed() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "intro.md", "---\nsidebar_position: 3\n---\n\n# Intro\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, staticSite(),
                List.of("intro.md"), bundle);

        assertThat(report).isEmpty();
    }

    /** An HTML microsite carries no front matter of its own, and this round does not check its parameters. */
    @Test
    void check_whenTheUploadIsAMicrosite_thenNothingIsChecked() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "notes.md", "---\nsidebar_position: banana\n---\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, microsite(),
                List.of("index.html", "notes.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenTheTreeHasAFinding_thenTheAcceptedReportsMetadataTravelsWith() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_position: banana\n---\n\n# Goals\n"));

        StructureReport report = validation.check(ACCEPTED, markdown(), List.of("1-intro/goals.md"), bundle)
                .orElseThrow();

        assertThat(report.template()).isEqualTo(ACCEPTED.template());
        assertThat(report.pathsChecked()).isEqualTo(ACCEPTED.pathsChecked());
        assertThat(report.allowedFolders()).isEqualTo(ACCEPTED.allowedFolders());
    }

    /** Only a page has front matter at all: an asset's bytes are never read as if they were some. */
    @Test
    void check_ignoresAnAssetWhateverItContains() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/sketch.png", "sidebar_position: banana"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(),
                List.of("1-intro/sketch.png"), bundle);

        assertThat(report).isEmpty();
    }
}
