package ch.admin.bit.jeap.doc.domain.upload.validation;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.OrderHint;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Would a page's own placement be accepted?
 * <p>
 * The template here is a small one of this test's own rather than arc42, the same way
 * {@code StructureValidationTest} keeps its own: what is under test is the cross-reference checks, and
 * arc42's twelve chapters would only make every case longer.
 */
@ExtendWith(MockitoExtension.class)
class OrderValidationTest {

    private static final StructureChapter INTRO = StructureChapter.numbered(1, "1-intro", "Introduction");
    private static final String SITE = "default";
    private static final String SYSTEM = "orders";

    private static final StructureReport ACCEPTED = new StructureReport("arc42", 1, 0,
            List.of("1-intro"), List.of("md"), List.of(), List.of(), 0);

    @Mock
    private CustomDocumentationRepository documentation;

    private OrderValidation validation;

    /**
     * Built here rather than as a field initializer: {@code @Mock} is injected after the test instance is
     * constructed, so a field initializer would still see {@code documentation} as null.
     */
    @BeforeEach
    void setUp() {
        validation = new OrderValidation(new StructureTemplates(List.of(new TestTemplate())), documentation);
    }

    private static DocumentationPlacement markdown() {
        return new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, SYSTEM, null, null, "arc42",
                SourceFormat.MARKDOWN, null, null);
    }

    private static DocumentationPlacement staticSite() {
        return new DocumentationPlacement(DocumentationType.SITE_DOCS, null, null, null, null,
                SourceFormat.MARKDOWN, null, null);
    }

    private static DocumentationPlacement microsite() {
        return new DocumentationPlacement(DocumentationType.COMPONENT_DOCS, SYSTEM, "orders-intake", null,
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

    /** Nothing is stored yet - the one stub every markdown test that reaches the cross-reference check needs. */
    private void storesNothing() {
        when(documentation.of(any(), any())).thenReturn(CustomDocumentation.nothing());
    }

    private void stores(CustomSet... sets) {
        when(documentation.of(any(), any())).thenReturn(new CustomDocumentation(List.of(sets)));
    }

    private static CustomSetKey markdownKey() {
        return CustomSetKey.of(SITE, markdown());
    }

    private static CustomSetKey micrositeKey(String topic) {
        return new CustomSetKey(SITE, SubjectKind.SYSTEM, SYSTEM, null, SourceFormat.HTML, "arc42",
                INTRO.folder(), topic);
    }

    private static CustomSet markdownSetOf(CustomPage... pages) {
        return new CustomSet(1L, markdownKey(), null, 1L, "uploads/docs/1/1/bundle.zip", "sha", 10L,
                new CustomProvenance(null, null, null, null, null, null), List.of(pages));
    }

    private static CustomSet micrositeOf(String topic, OrderHint hint) {
        return new CustomSet(2L, micrositeKey(topic), "Handbook", 1L, "uploads/docs/2/1/bundle.zip", "sha",
                10L, new CustomProvenance(null, null, null, null, null, null), List.of(), hint);
    }

    @Test
    void check_whenNoPageNamesAPlacement_thenNothing() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\ntitle: Goals\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenASidebarPositionIsNotANumber_thenInvalidPosition() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_position: banana\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code, StructureFinding::path)
                .containsExactly(tuple(FindingCode.INVALID_POSITION, "1-intro/goals.md"));
    }

    @Test
    void check_whenASidebarPositionIsADecimal_thenNotInvalid() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_position: 2.5\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenBothDirectionsAreNamed_thenInvalidReference() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: a\nsidebar_before: b\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code)
                .containsExactly(FindingCode.INVALID_REFERENCE);
    }

    @Test
    void check_whenAReferenceIsAList_thenInvalidReference() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after:\n  - one\n  - two\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code)
                .containsExactly(FindingCode.INVALID_REFERENCE);
    }

    /** {@code intro} is a sibling the test template generates into this chapter for a system. */
    @Test
    void check_whenAValidReferenceIsNamed_thenNothing() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: intro\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    /** A static site has no generated siblings and no chapter of its own: a reference names nothing real. */
    @Test
    void check_whenAStaticSitePageNamesAReference_thenInvalidReferenceEvenIfWellFormed() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "intro.md", "---\nsidebar_after: intro\n---\n\n# Intro\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, staticSite(), SITE,
                List.of("intro.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code)
                .containsExactly(FindingCode.INVALID_REFERENCE);
    }

    @Test
    void check_whenAStaticSitePageNamesAPosition_thenStillAllowed() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "intro.md", "---\nsidebar_position: 3\n---\n\n# Intro\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, staticSite(), SITE,
                List.of("intro.md"), bundle);

        assertThat(report).isEmpty();
    }

    /** An HTML microsite carries no front matter of its own, and this round does not check its parameters. */
    @Test
    void check_whenTheUploadIsAMicrosite_thenNothingIsChecked() {
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "notes.md", "---\nsidebar_position: banana\n---\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, microsite(), SITE,
                List.of("index.html", "notes.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenTheTreeHasAFinding_thenTheAcceptedReportsMetadataTravelsWith() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_position: banana\n---\n\n# Goals\n"));

        StructureReport report = validation.check(ACCEPTED, markdown(), SITE, List.of("1-intro/goals.md"), bundle)
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

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/sketch.png"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenAReferenceNamesNothingKnown_thenUnknownReference() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: nonexistent\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code, StructureFinding::path)
                .containsExactly(tuple(FindingCode.UNKNOWN_REFERENCE, "1-intro/goals.md"));
    }

    @Test
    void check_whenAReferenceNamesASiblingOfTheSameUpload_thenNothing() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\ntitle: Goals\n---\n\n# Goals\n",
                "1-intro/context.md", "---\nsidebar_after: goals\n---\n\n# Context\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md", "1-intro/context.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenAReferenceNamesAPageOfTheCurrentlyStoredSet_thenNothing() {
        stores(markdownSetOf(new CustomPage("1-intro", "overview.md", "Overview", 1, false)));
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: overview\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenAReferenceNamesACurrentlyStoredMicrosite_thenNothing() {
        stores(micrositeOf("handbook", OrderHint.NONE));
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: microsites/handbook\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isEmpty();
    }

    @Test
    void check_whenAPageReferencesItself_thenCyclicOrder() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/goals.md", "---\nsidebar_after: goals\n---\n\n# Goals\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/goals.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code, StructureFinding::path)
                .containsExactly(tuple(FindingCode.CYCLIC_ORDER, "1-intro/goals.md"));
    }

    @Test
    void check_whenTwoPagesReferenceEachOther_thenBothAreCyclicOrder() {
        storesNothing();
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/a.md", "---\nsidebar_after: b\n---\n\n# A\n",
                "1-intro/b.md", "---\nsidebar_after: a\n---\n\n# B\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/a.md", "1-intro/b.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code, StructureFinding::path)
                .containsExactlyInAnyOrder(
                        tuple(FindingCode.CYCLIC_ORDER, "1-intro/a.md"),
                        tuple(FindingCode.CYCLIC_ORDER, "1-intro/b.md"));
    }

    /** The cycle is only completed by combining this upload's own reference with what is already stored. */
    @Test
    void check_whenAStoredPageReferencesBackIntoThisUpload_thenCyclicOrder() {
        stores(markdownSetOf(new CustomPage("1-intro", "b.md", "B", 1, false,
                new OrderHint(null, OrderHint.Direction.AFTER, "a"))));
        UploadedBundles.ReceivedBundle bundle = bundleOf(Map.of(
                "1-intro/a.md", "---\nsidebar_after: b\n---\n\n# A\n"));

        Optional<StructureReport> report = validation.check(ACCEPTED, markdown(), SITE,
                List.of("1-intro/a.md"), bundle);

        assertThat(report).isPresent();
        assertThat(report.get().findings()).extracting(StructureFinding::code, StructureFinding::path)
                .containsExactly(tuple(FindingCode.CYCLIC_ORDER, "1-intro/a.md"));
    }

    /** A small template of this test's own: arc42's twelve chapters would only make every case longer. */
    private static final class TestTemplate implements StructureTemplate {

        @Override
        public String id() {
            return "arc42";
        }

        @Override
        public String systemPathSegment() {
            return "architecture";
        }

        @Override
        public String systemLabel() {
            return "Architecture";
        }

        @Override
        public String componentPathSegment() {
            return "architecture";
        }

        @Override
        public String componentLabel() {
            return "Architecture";
        }

        @Override
        public String libraryPathSegment() {
            return "architecture";
        }

        @Override
        public String libraryLabel() {
            return "Architecture";
        }

        @Override
        public List<StructureChapter> chapters() {
            return List.of(INTRO);
        }

        @Override
        public Set<String> allowedFileExtensions() {
            return Set.of("md");
        }

        @Override
        public List<String> generatedSiblings(StructureChapter chapter, SubjectKind subject) {
            return INTRO.equals(chapter) && subject == SubjectKind.SYSTEM ? List.of("intro") : List.of();
        }

        @Override
        public void writeSystem(SystemDocumentation system, GenerationContext context, Path directory) {
            // not exercised here: this test is about the cross-reference checks, not the generated pages.
        }
    }
}
