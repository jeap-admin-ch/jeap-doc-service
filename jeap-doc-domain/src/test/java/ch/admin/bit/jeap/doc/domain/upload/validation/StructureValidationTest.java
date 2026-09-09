package ch.admin.bit.jeap.doc.domain.upload.validation;

import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Would this path tree be accepted?
 * <p>
 * The template here is a small one of this test's own rather than arc42: what is under test is the deciding,
 * the wording and the ordering, and arc42's twelve chapters would only make every case longer. That arc42
 * declares the right chapters and the right generated names is {@code Arc42TemplateTest}'s business.
 */
class StructureValidationTest {

    private static final StructureChapter INTRO = StructureChapter.numbered(1, "1-intro", "Introduction");
    /** Numbered 4 so that a folder naming chapter 5 under the number 4 has a wrong chapter to be sent to. */
    private static final StructureChapter STRATEGY =
            StructureChapter.numbered(4, "4-solution-strategy", "Solution Strategy");
    private static final StructureChapter BUILDING_BLOCKS =
            StructureChapter.numbered(5, "5-building-block-view", "Building Block View");

    private final UploadProperties properties = new UploadProperties();
    private final StructureValidation validation =
            new StructureValidation(new StructureTemplates(List.of(new TestTemplate())), properties);

    private static DocumentationPlacement systemDocs() {
        return new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null, null, "test",
                SourceFormat.MARKDOWN, null, null);
    }

    private static DocumentationPlacement componentDocs() {
        return new DocumentationPlacement(DocumentationType.COMPONENT_DOCS, "orders", "orders-intake", null,
                "test", SourceFormat.MARKDOWN, null, null);
    }

    private static DocumentationPlacement libraryDocs() {
        return new DocumentationPlacement(DocumentationType.LIBRARY_DOCS, "orders", null, "orders-client",
                "test", SourceFormat.MARKDOWN, null, null);
    }

    private static DocumentationPlacement html(String location) {
        return new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null, null, "test",
                SourceFormat.HTML, location, "load-test");
    }

    private StructureReport validate(DocumentationPlacement placement, String... paths) {
        return validation.validate(placement, List.of(paths));
    }

    private static List<FindingCode> codesOf(StructureReport report) {
        return report.findings().stream().map(StructureFinding::code).toList();
    }

    @Test
    void aTreeThatFollowsTheTemplate_yieldsNothing() {
        StructureReport report = validate(systemDocs(),
                "1-intro/goals.md",
                "5-building-block-view/design.md",
                "5-building-block-view/overview.png");

        assertThat(report.isValid()).isTrue();
        assertThat(report.findings()).isEmpty();
        assertThat(report.pathsChecked()).isEqualTo(3);
        assertThat(report.pathsIgnored()).isZero();
        assertThat(report.template()).isEqualTo("test");
        assertThat(report.allowedFolders())
                .containsExactly("1-intro", "4-solution-strategy", "5-building-block-view");
        assertThat(report.allowedExtensions()).containsExactly("md", "png");
    }

    /**
     * <b>Missing chapters are not an error.</b> A repository documents what it documents, and a report that
     * complained about the eleven chapters nobody wrote would be a report nobody reads.
     */
    @Test
    void aTreeThatUsesOneChapter_yieldsNothing() {
        assertThat(validate(systemDocs(), "1-intro/goals.md").findings()).isEmpty();
    }

    @Test
    void anUnknownTemplate_isTheOnlyThingTheReportSays() {
        DocumentationPlacement unknown = new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders",
                null, null, "arc24", SourceFormat.MARKDOWN, null, null);

        StructureReport report = validation.validate(unknown, List.of("1-intro/goals.md"));

        assertThat(codesOf(report)).containsExactly(FindingCode.UNKNOWN_TEMPLATE);
        assertThat(report.findings().getFirst().path()).describedAs("about the tree, not about a path").isNull();
        assertThat(report.findings().getFirst().message()).contains("arc24").contains("test");
        assertThat(report.allowedFolders()).describedAs("there is no template to ask").isEmpty();
    }

    @Test
    void aPathThatIsNotRelativeAndNormalized_isRefused() {
        assertThat(codesOf(validate(systemDocs(), "/1-intro/goals.md")))
                .containsExactly(FindingCode.INVALID_PATH);
        assertThat(codesOf(validate(systemDocs(), "1-intro/../../etc/passwd")))
                .containsExactly(FindingCode.INVALID_PATH);
        assertThat(codesOf(validate(systemDocs(), "1-intro\\goals.md")))
                .containsExactly(FindingCode.INVALID_PATH);
        assertThat(codesOf(validate(systemDocs(), "1-intro//goals.md")))
                .containsExactly(FindingCode.INVALID_PATH);
        assertThat(codesOf(validate(systemDocs(), "1-intro/goals.md\n")))
                .containsExactly(FindingCode.INVALID_PATH);
        assertThat(codesOf(validate(systemDocs(), "1-intro/")))
                .containsExactly(FindingCode.INVALID_PATH);
    }

    /** One absurd path among sane ones is a mistake in one path, not a tree to refuse. */
    @Test
    void aPathLongerThanTheLimit_isOneFinding() {
        String tooLong = "1-intro/" + "x".repeat(StructureValidation.MAX_PATH_LENGTH) + ".md";

        StructureReport report = validate(systemDocs(), "1-intro/goals.md", tooLong);

        assertThat(codesOf(report)).containsExactly(FindingCode.INVALID_PATH);
    }

    @Test
    void aPathThatAppearsTwice_isOneFinding() {
        StructureReport report = validate(systemDocs(), "1-intro/goals.md", "1-intro/goals.md",
                "1-intro/goals.md");

        assertThat(codesOf(report)).containsExactly(FindingCode.DUPLICATE_PATH);
        assertThat(report.pathsChecked()).describedAs("what arrived, not what was distinct").isEqualTo(3);
    }

    @Test
    void aFileAtTheRootOfTheSet_belongsToNoChapter() {
        assertThat(codesOf(validate(systemDocs(), "README.md")))
                .containsExactly(FindingCode.FILE_OUTSIDE_CHAPTER);
    }

    @Test
    void aFolderThatIsNoChapter_isRefusedAndTheMessageGuesses() {
        StructureReport report = validate(systemDocs(), "4-building-block-view/design.md");

        assertThat(codesOf(report)).containsExactly(FindingCode.UNKNOWN_CHAPTER);
        assertThat(report.findings().getFirst().message())
                .isEqualTo("'4-building-block-view' is not a chapter of test. "
                           + "Did you mean '5-building-block-view'?");
    }

    /** The three ways a chapter is written by mistake: its URL segment, its number, the slug of its title. */
    @Test
    void aChapterWrittenTheWrongWay_isNamedInTheMessage() {
        assertThat(validate(systemDocs(), "building-block-view/design.md").findings().getFirst().message())
                .describedAs("the URL segment, which is what a reader sees in the address bar")
                .contains("Did you mean '5-building-block-view'?");
        assertThat(validate(systemDocs(), "5-bausteinsicht/design.md").findings().getFirst().message())
                .describedAs("the number, which is what somebody types from memory")
                .contains("Did you mean '5-building-block-view'?");
        assertThat(validate(systemDocs(), "introduction/goals.md").findings().getFirst().message())
                .describedAs("the slug of the title")
                .contains("Did you mean '1-intro'?");
        assertThat(validate(systemDocs(), "somewhere-else/goals.md").findings().getFirst().message())
                .describedAs("and nothing is guessed when nothing matches")
                .doesNotContain("Did you mean");
    }

    /**
     * A name under the wrong number means the chapter it names. Reading the number first would answer
     * {@code 4-building-block-view} with chapter 4 - a chapter the upload said nothing about.
     */
    @Test
    void aChapterNamedUnderTheWrongNumber_isSentToTheChapterItNames() {
        assertThat(validate(systemDocs(), "4-building-block-view/design.md").findings().getFirst().message())
                .contains("Did you mean '5-building-block-view'?")
                .doesNotContain("4-solution-strategy");
        assertThat(validate(systemDocs(), "9-introduction/goals.md").findings().getFirst().message())
                .describedAs("the slug of a title counts as its name too")
                .contains("Did you mean '1-intro'?");
    }

    @Test
    void aFolderInsideAChapter_isRefused() {
        StructureReport report = validate(systemDocs(), "5-building-block-view/notes/design.md");

        assertThat(codesOf(report)).containsExactly(FindingCode.NESTED_FOLDER);
        assertThat(report.findings().getFirst().message()).contains("notes").contains("beside the page");
    }

    /**
     * <b>The images folder a repository reaches for by habit.</b> It is the most likely mistake of all, so the
     * message has to say where the picture goes instead.
     */
    @Test
    void theImagesFolder_isRefusedWithSomewhereToPutThePicture() {
        assertThat(codesOf(validate(systemDocs(), "5-building-block-view/images/overview.png")))
                .containsExactly(FindingCode.NESTED_FOLDER);
    }

    @Test
    void aHiddenFileThatNoToolWrote_isRefused() {
        StructureReport report = validate(systemDocs(), "1-intro/.notes.md");

        assertThat(codesOf(report)).containsExactly(FindingCode.HIDDEN_NAME);
        assertThat(report.findings().getFirst().message()).contains(".notes.md");
    }

    /**
     * <b>A name the site generator keeps for itself.</b> An underscore-prefixed page is excluded from the
     * build, so the upload succeeds and publishes nothing - the worst outcome there is.
     */
    @Test
    void aNameBeginningWithAnUnderscore_isRefused() {
        assertThat(codesOf(validate(systemDocs(), "1-intro/_draft.md")))
                .containsExactly(FindingCode.UNPUBLISHABLE_NAME);
        assertThat(codesOf(validate(systemDocs(), "1-intro/_category_.json")))
                .describedAs("the navigation file is caught by the same rule, and needs no reservation of its own")
                .containsExactly(FindingCode.UNPUBLISHABLE_NAME);
    }

    @Test
    void anExtensionTheTemplateDoesNotTake_isRefused() {
        assertThat(codesOf(validate(systemDocs(), "1-intro/goals.mdx")))
                .containsExactly(FindingCode.FORBIDDEN_EXTENSION);
        assertThat(codesOf(validate(systemDocs(), "1-intro/goals")))
                .describedAs("a file with no extension fails it too")
                .containsExactly(FindingCode.FORBIDDEN_EXTENSION);
        assertThat(codesOf(validate(systemDocs(), "1-intro/GOALS.MD")))
                .describedAs("the extension is folded, the name is not")
                .isEmpty();
    }

    @Test
    void aDocumentTheGeneratorWrites_isRefused() {
        StructureReport report = validate(systemDocs(), "5-building-block-view/whitebox-view.md");

        assertThat(codesOf(report)).containsExactly(FindingCode.RESERVED_NAME);
        assertThat(report.findings().getFirst().message()).contains("whitebox-view")
                .contains("5-building-block-view");
    }

    @Test
    void theChaptersLandingPage_isReservedForEveryTemplate() {
        assertThat(codesOf(validate(systemDocs(), "1-intro/index.md")))
                .containsExactly(FindingCode.RESERVED_NAME);
    }

    /**
     * <b>Every name the site generator reads as a landing page is reserved.</b> Docusaurus takes
     * {@code index}, {@code readme} or the folder's own name, folded, so all of them resolve to the URL of the
     * generated landing page and would fail the build of that part as a duplicate route.
     */
    @Test
    void everyNameThatIsReadAsALandingPage_isReserved() {
        assertThat(codesOf(validate(systemDocs(), "1-intro/README.md")))
                .containsExactly(FindingCode.RESERVED_NAME);
        assertThat(codesOf(validate(systemDocs(), "1-intro/readme.md")))
                .containsExactly(FindingCode.RESERVED_NAME);
        assertThat(codesOf(validate(systemDocs(), "1-intro/INDEX.MD")))
                .describedAs("the generator folds the case, so the rule does too")
                .containsExactly(FindingCode.RESERVED_NAME);
        assertThat(codesOf(validate(systemDocs(), "1-intro/1-intro.md")))
                .describedAs("a document of the folder's own name is that folder's index")
                .containsExactly(FindingCode.RESERVED_NAME);
        assertThat(validate(systemDocs(), "1-intro/readme.png").findings())
                .describedAs("and only a document can collide with a document")
                .isEmpty();
    }

    /**
     * <b>A number prefix is not part of the page's name.</b> Docusaurus parses a leading number off a
     * document's file name and uses what is left as the document's id and its route, so {@code 01-rest-api.md}
     * is the document {@code rest-api} - which is the page the doc service generates into that chapter. The
     * rule compared the file name as written, so the upload was told it would be accepted and the build it
     * later fed would fail on two documents of one id.
     */
    @Test
    void aNumberedNameOfAGeneratedPage_isReserved() {
        assertThat(codesOf(validate(componentDocs(), "5-building-block-view/01-rest-api.md")))
                .containsExactly(FindingCode.RESERVED_NAME);
        assertThat(codesOf(validate(componentDocs(), "5-building-block-view/1-rest-api.md")))
                .describedAs("one digit or two, the generator parses both")
                .containsExactly(FindingCode.RESERVED_NAME);
        assertThat(codesOf(validate(componentDocs(), "5-building-block-view/1_rest-api.md")))
                .describedAs("and an underscore or a dot separates a prefix just as a hyphen does")
                .containsExactly(FindingCode.RESERVED_NAME);
    }

    /**
     * The same for the landing page of a chapter: {@code 01-index.md} is not <i>read</i> as the landing page -
     * that is decided on the name as written - but it is identified as {@code index} all the same, which is
     * the document the generated landing page already is.
     */
    @Test
    void aNumberedIndex_isReserved() {
        assertThat(codesOf(validate(systemDocs(), "1-intro/01-index.md")))
                .containsExactly(FindingCode.RESERVED_NAME);
    }

    /**
     * <b>What a date or a version looks like is left alone</b>, because the generator leaves it alone: a
     * second number after the separator is not read as a prefix, so these are pages of their own name and
     * collide with nothing.
     */
    @Test
    void aNameThatOnlyLooksNumbered_isNotReserved() {
        assertThat(validate(componentDocs(), "5-building-block-view/2021-11-rest-api.md").findings())
                .describedAs("a date is not a number prefix")
                .isEmpty();
        assertThat(validate(componentDocs(), "5-building-block-view/7.0-rest-api.md").findings())
                .describedAs("nor is a version")
                .isEmpty();
    }

    /**
     * <b>Two files, one page.</b> {@code foo.md} and {@code 1-foo.md} are two paths and neither is reserved,
     * so the duplicate-path rule saw nothing - and the generator identifies both as {@code foo} and fails the
     * build on a duplicate document id.
     * <p>
     * <b>Both files are named</b>, one finding each, unlike a path that simply appears twice: these are two
     * different files, and which of them to rename is the author's choice - so a report that named only one
     * of them would be telling half the story.
     */
    @Test
    void twoNamesThatDifferOnlyInANumberPrefix_collide() {
        assertThat(validate(systemDocs(), "1-intro/foo.md", "1-intro/1-foo.md").findings())
                .extracting(StructureFinding::code, StructureFinding::path)
                .containsExactlyInAnyOrder(tuple(FindingCode.COLLIDING_NAME, "1-intro/foo.md"),
                        tuple(FindingCode.COLLIDING_NAME, "1-intro/1-foo.md"));
        assertThat(codesOf(validate(systemDocs(), "1-intro/1-foo.md", "1-intro/2-foo.md")))
                .describedAs("two prefixes of one name are the same page just as well")
                .containsExactly(FindingCode.COLLIDING_NAME, FindingCode.COLLIDING_NAME);
    }

    /** Within one chapter. The same name in two chapters is two pages at two URLs. */
    @Test
    void oneNameInTwoChapters_doesNotCollide() {
        assertThat(validate(systemDocs(), "1-intro/foo.md", "4-solution-strategy/1-foo.md").findings())
                .isEmpty();
    }

    /**
     * And a collision is about documents: an image keeps the name it has, so an image and a document that
     * differ only in a number prefix are two files at two URLs.
     */
    @Test
    void anImageAndADocumentThatDifferOnlyInANumberPrefix_doNotCollide() {
        assertThat(validate(systemDocs(), "1-intro/foo.md", "1-intro/1-foo.png").findings())
                .isEmpty();
    }

    /**
     * <b>The rule is about documents, not about files.</b> Only a Markdown file becomes a route, so an image
     * named after a generated page is an image and nothing collides.
     */
    @Test
    void anImageNamedAfterAGeneratedPage_isNotAFinding() {
        assertThat(validate(systemDocs(), "5-building-block-view/whitebox-view.png").findings()).isEmpty();
    }

    /** What is reserved depends on what is being documented: a component's pages are not a system's. */
    @Test
    void whatIsReserved_dependsOnTheKindOfSubject() {
        assertThat(codesOf(validate(componentDocs(), "5-building-block-view/rest-api.md")))
                .containsExactly(FindingCode.RESERVED_NAME);
        assertThat(validate(systemDocs(), "5-building-block-view/rest-api.md").findings())
                .describedAs("no page of that name is generated for a system")
                .isEmpty();
        assertThat(validate(componentDocs(), "5-building-block-view/whitebox-view.md").findings())
                .describedAs("nor a whitebox view for a component")
                .isEmpty();
    }

    /** Nothing is generated for a library, so only the landing page names are reserved for one. */
    @Test
    void aLibrary_hasNothingReservedBeyondTheLandingPage() {
        assertThat(validate(libraryDocs(), "5-building-block-view/whitebox-view.md").findings())
                .describedAs("a page generated for a system is a page a library may write itself")
                .isEmpty();
        assertThat(codesOf(validate(libraryDocs(), "1-intro/index.md")))
                .containsExactly(FindingCode.RESERVED_NAME);
    }

    @Test
    void aTreeWithNoPath_isEmpty() {
        assertThat(codesOf(validation.validate(systemDocs(), List.of())))
                .containsExactly(FindingCode.EMPTY_TREE);
        assertThat(codesOf(validation.validate(systemDocs(), null)))
                .containsExactly(FindingCode.EMPTY_TREE);
    }

    /**
     * The tooling artefacts are dropped, not reported - and counted, because a count that omits what it
     * skipped is the same lie as a truncated list of findings.
     */
    @Test
    void theIgnoredPaths_yieldNothingAndAreCounted() {
        StructureReport report = validate(systemDocs(), "1-intro/goals.md", "1-intro/.DS_Store",
                "__MACOSX/1-intro/goals.md");

        assertThat(report.findings()).isEmpty();
        assertThat(report.pathsChecked()).isEqualTo(1);
        assertThat(report.pathsIgnored()).isEqualTo(2);
    }

    /** A ZIP of nothing but Finder junk publishes nothing, and the message says which case it is. */
    @Test
    void aTreeOfNothingButIgnoredPaths_isEmptyAndSaysWhy() {
        StructureReport report = validate(systemDocs(), ".DS_Store", "__MACOSX/x");

        assertThat(codesOf(report)).containsExactly(FindingCode.EMPTY_TREE);
        assertThat(report.findings().getFirst().message()).contains("all 2 of its files");
        assertThat(report.pathsIgnored()).isEqualTo(2);
    }

    /**
     * A null is not a path, and a finding with no path is one the report presents as being about the whole
     * tree. So it is dropped and counted instead of reported.
     */
    @Test
    void aNullAmongThePaths_isDroppedRatherThanReportedWithoutOne() {
        StructureReport report = validation.validate(systemDocs(), Arrays.asList("1-intro/goals.md", null));

        assertThat(report.findings()).isEmpty();
        assertThat(report.pathsChecked()).isEqualTo(1);
        assertThat(report.pathsIgnored()).isEqualTo(1);
    }

    @Test
    void theFindings_areOrderedByPathThenCodeAndTheTreesComeFirst() {
        StructureReport report = validate(systemDocs(), "5-building-block-view/whitebox-view.md",
                "1-intro/_draft.md", "README.md");

        assertThat(report.findings()).extracting(StructureFinding::path)
                .containsExactly("1-intro/_draft.md", "5-building-block-view/whitebox-view.md", "README.md");
    }

    /** A truncated list that does not say it is truncated is a lie. */
    @Test
    void theFindings_areCappedAndTheReportSaysHowMany() {
        properties.getValidation().setMaxFindings(2);

        StructureReport report = validate(systemDocs(), "a.md", "b.md", "c.md", "d.md");

        assertThat(report.findings()).hasSize(2);
        assertThat(report.findingsOmitted()).isEqualTo(2);
        assertThat(codesOf(report)).containsOnly(FindingCode.FILE_OUTSIDE_CHAPTER);
    }

    @Test
    void html_isCheckedAsAMicrositeAndNotAgainstTheChapters() {
        StructureReport report = validate(html("5-building-block-view"), "index.html", "app/main.js",
                "assets/deep/nested/style.css", "img/logo.png");

        assertThat(report.findings()).describedAs("a microsite nests as deeply as it likes").isEmpty();
        assertThat(report.allowedExtensions()).contains("html", "js", "css");
    }

    @Test
    void html_withoutAnEntryPoint_isRefused() {
        assertThat(codesOf(validate(html("5-building-block-view"), "app/main.js")))
                .contains(FindingCode.MISSING_ENTRY_POINT);
        assertThat(codesOf(validate(html("5-building-block-view"), "docs/index.html")))
                .describedAs("at the root of the set, not somewhere in it")
                .contains(FindingCode.MISSING_ENTRY_POINT);
    }

    /** The two hygiene rules hold for an HTML upload too: it follows no chapters, but a path is still a path. */
    @Test
    void html_withAPathThatIsNotOneOrAppearsTwice_isRefused() {
        assertThat(codesOf(validate(html("5-building-block-view"), "index.html", "/app/main.js")))
                .containsExactly(FindingCode.INVALID_PATH);
        assertThat(codesOf(validate(html("5-building-block-view"), "index.html", "app/main.js",
                "app/main.js")))
                .containsExactly(FindingCode.DUPLICATE_PATH);
    }

    @Test
    void html_withAnAssetThatIsNotOne_isRefused() {
        assertThat(codesOf(validate(html("5-building-block-view"), "index.html", "notes.md")))
                .containsExactly(FindingCode.FORBIDDEN_EXTENSION);
    }

    @Test
    void html_embeddedWhereThereIsNoChapter_isRefused() {
        assertThat(codesOf(validate(html("7-deployment-view"), "index.html")))
                .containsExactly(FindingCode.UNKNOWN_LOCATION);
    }

    /** A static export legitimately carries `_astro/` and `.well-known/`; the chapter rules do not reach it. */
    @Test
    void html_mayCarryTheNamesAMarkdownUploadMayNot() {
        StructureReport report = validate(html("5-building-block-view"), "index.html", "_astro/app.js",
                ".well-known/security.txt");

        assertThat(report.findings()).isEmpty();
    }

    /** A small template of this test's own: two chapters, two extensions, one generated page. */
    private static final class TestTemplate implements StructureTemplate {

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String systemPathSegment() {
            return "system-architecture";
        }

        @Override
        public String systemLabel() {
            return "System Architecture";
        }

        @Override
        public String componentPathSegment() {
            return "component-architecture";
        }

        @Override
        public String componentLabel() {
            return "Component Architecture";
        }

        @Override
        public List<StructureChapter> chapters() {
            return List.of(INTRO, STRATEGY, BUILDING_BLOCKS);
        }

        @Override
        public Set<String> allowedFileExtensions() {
            return Set.of("md", "png");
        }

        @Override
        public Set<String> generatedNames(StructureChapter chapter, SubjectKind subject) {
            if (!BUILDING_BLOCKS.equals(chapter)) {
                return Set.of();
            }
            return switch (subject) {
                case SYSTEM -> Set.of("whitebox-view");
                case COMPONENT -> Set.of("rest-api");
                case LIBRARY -> Set.of();
            };
        }

        @Override
        public void writeSystem(DocumentedSystem system, GenerationContext context, Path directory)
                throws IOException {
            // Nothing is generated here; what this template is for is the rules.
        }
    }
}
