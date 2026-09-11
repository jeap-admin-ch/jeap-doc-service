package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a page a team uploaded looks like once it is written into a tree.
 * <p>
 * The class that decides that, unit tested: the front matter it generates, the names it refuses to publish
 * over, the order it assigns and what it does with a set that is larger than a set may be. Every one of those
 * is a rule about one file, and reaching them through a whole site build would leave most of them untouched.
 */
class CustomPagesWriterTest {

    private static final String SITE = "default";
    private static final String SYSTEM_SLUG = "orders";

    private static final CustomSubject SYSTEM =
            new CustomSubject(SITE, SubjectKind.SYSTEM, SYSTEM_SLUG, null);
    private static final CustomSubject COMPONENT =
            new CustomSubject(SITE, SubjectKind.COMPONENT, SYSTEM_SLUG, "orders-intake");

    private static final CustomProvenance PROVENANCE = new CustomProvenance("orders-docs", "main",
            "cafebabe", Instant.parse("2026-09-01T10:00:00Z"), "1.4.0",
            Instant.parse("2026-09-01T10:05:00Z"));

    @TempDir
    Path chapterDirectory;

    /**
     * <b>The one that was missed.</b> A subject legitimately carries several sets - here the Markdown of a
     * component and the HTML microsite beside it, which is the shape of the workspace's own example
     * repository - and both of them name the same template, because every upload names one. Asking for
     * <i>the</i> set of a subject answered with whichever the database happened to hand back first: with the
     * microsite first, every Markdown page of that component was silently dropped.
     */
    @Test
    void writeInto_whenTheSubjectAlsoHasAMicrosite_thenItsMarkdownPagesAreStillWritten() {
        CustomSet markdown = setOf(2L, COMPONENT, SourceFormat.MARKDOWN,
                page("1-intro", "why.md", "Why", 1));
        CustomSet microsite = setOf(1L, COMPONENT, SourceFormat.HTML,
                new CustomPage("6-runtime-view", "index.html", null, 0, true));
        // The microsite first, which is the order that broke it.
        InMemoryStorage storage = new InMemoryStorage()
                .with(microsite, "6-runtime-view/index.html", "<html lang=\"en\"></html>")
                .with(markdown, "1-intro/why.md", "---\ntitle: Why\n---\n\nBecause.\n");

        int written;
        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(microsite, markdown)),
                storage)) {
            written = writer.writeInto(COMPONENT, "1-intro", chapterDirectory);
        }

        assertThat(written).isEqualTo(1);
        assertThat(chapterDirectory.resolve("why.md")).exists();
    }

    /** A set following another methodology is another tree's, and this writer leaves it alone. */
    @Test
    void writeInto_whenTheSetFollowsAnotherTemplate_thenNothingIsWritten() {
        CustomSet other = new CustomSet(1L,
                new CustomSetKey(SITE, SubjectKind.SYSTEM, SYSTEM_SLUG, null, SourceFormat.MARKDOWN,
                        "something-else", null, null),
                7L, "current/whatever", "abc", 10, PROVENANCE,
                List.of(page("1-intro", "why.md", "Why", 1)));
        InMemoryStorage storage = new InMemoryStorage().with(other, "1-intro/why.md", "# Why\n");

        int written;
        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(other)), storage)) {
            written = writer.writeInto(SYSTEM, "1-intro", chapterDirectory);
        }

        assertThat(written).isZero();
        assertThat(chapterDirectory).isEmptyDirectory();
    }

    /** The body is what the team wrote, and the front matter is what this service decides. */
    @Test
    void writeInto_thenTheFrontMatterIsGeneratedAndTheBodyIsTheTeamsOwn() throws IOException {
        String uploaded = """
                ---
                title: Why we built this
                description: The goals of the system
                slug: /take-over-this-route
                doc_status: generated
                ---

                Because the old one could not do it.
                """;

        writeOnePage("1-intro", "why.md", uploaded);

        String published = Files.readString(chapterDirectory.resolve("why.md"));
        assertThat(published)
                .describedAs("the keys the upload may carry, kept as they were written")
                .contains("title: Why we built this")
                .contains("description: The goals of the system");
        assertThat(published)
                .describedAs("an allowlist, so a page cannot claim to be generated or seize a route")
                .doesNotContain("slug:")
                .doesNotContain("doc_status: generated");
        assertThat(published)
                .contains("doc_status: custom")
                .contains("doc_source: upload")
                .contains("doc_source_repository: orders-docs")
                .contains("doc_source_ref: main")
                .contains("doc_source_revision: cafebabe")
                .describedAs("a version is quoted, because plainly written it would read as a number")
                .contains("doc_version: 1.4.0");
        assertThat(published).endsWith("Because the old one could not do it.\n");
    }

    /**
     * Past whatever the template generates into the chapter. Docusaurus breaks a tie between two equal
     * positions by file name, which is the one thing assigning a position is there to take out of it - so an
     * uploaded page numbered from one would tie with the first generated page of that chapter.
     */
    @Test
    void writeInto_thenTheSidebarPositionStandsAfterTheGeneratedPagesOfTheChapter() throws IOException {
        writeOnePage("5-building-block-view", "our-take.md", "---\ntitle: Our take\n---\n\nText.\n");

        assertThat(Files.readString(chapterDirectory.resolve("our-take.md")))
                .contains("sidebar_position: " + (new TestTemplate().firstCustomPagePosition() + 1));
    }

    /**
     * A repository URL holds a colon and an instant looks like a date, and either written carelessly is a
     * page that fails the build of its part - or one whose uploaded timestamp reaches the site template as a
     * date object. The quoting is the front matter's own business, which is why it is written through a YAML
     * dumper rather than assembled here.
     */
    @Test
    void writeInto_thenTheGeneratedValuesCarryTheQuotingTheyNeed() throws IOException {
        CustomSet set = new CustomSet(1L, keyOf(SYSTEM, SourceFormat.MARKDOWN), 7L, "current/whatever",
                "abc", 10,
                new CustomProvenance("https://github.com/orders/docs", "refs/heads/main", "cafebabe",
                        Instant.EPOCH, null, Instant.EPOCH),
                List.of(page("1-intro", "why.md", "Why", 1)));
        InMemoryStorage storage = new InMemoryStorage().with(set, "1-intro/why.md", "# Why\n");
        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            writer.writeInto(SYSTEM, "1-intro", chapterDirectory);
        }

        assertThat(Files.readString(chapterDirectory.resolve("why.md")))
                .describedAs("a URL is a plain scalar - its colon is not followed by a space")
                .contains("doc_source_repository: https://github.com/orders/docs");
        assertThat(Files.readString(chapterDirectory.resolve("why.md")))
                .describedAs("an instant is quoted, or YAML reads it back as a timestamp")
                .contains("doc_uploaded_at: '1970-01-01T00:00:00Z'");
        assertThat(Files.readString(chapterDirectory.resolve("why.md")))
                .describedAs("a set whose upload carried no version says nothing about one")
                .doesNotContain("doc_version:");
    }

    /**
     * The backstop against a page over a generated one, which the upload refuses and a set stored before the
     * rule existed can still carry. It has to know every part of the rule: the landing-page names, the folded
     * case, and the number prefix a document loses on its way to a URL - a page it lets through fails the
     * whole part on a duplicate route, naming a route rather than an upload.
     */
    @Test
    void writeInto_whenThePageWouldBeASecondDocumentAtOneUrl_thenItIsLeftOut() {
        List<String> refused =
                List.of("index.md", "README.md", "readme.md", "01-index.md", "1-intro.md", "whitebox.md",
                        "02-whitebox.md", "INDEX.MD");

        for (String fileName : refused) {
            Path directory = chapterDirectory.resolve(fileName.toLowerCase(java.util.Locale.ROOT) + ".d");
            CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                    page("1-intro", fileName, "Whatever", 1));
            InMemoryStorage storage = new InMemoryStorage().with(set, "1-intro/" + fileName, "# Whatever\n");
            try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
                assertThat(writer.writeInto(SYSTEM, "1-intro", directory))
                        .describedAs("%s would be a second document at a page this template writes", fileName)
                        .isZero();
            }
        }
    }

    /** A name that is not occupied is published, prefix and all. */
    @Test
    void writeInto_whenTheNameIsItsOwn_thenItIsPublished() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN, page("1-intro", "01-goals.md", "Goals", 1));
        InMemoryStorage storage = new InMemoryStorage().with(set, "1-intro/01-goals.md", "# Goals\n");

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory)).isEqualTo(1);
        }
        assertThat(chapterDirectory.resolve("01-goals.md")).exists();
    }

    /**
     * <b>What this drops is exactly what the upload refuses, and no more.</b> The landing-page names are
     * folded, because the site generator folds them; a generated page's name is compared as it is, because
     * that is how the upload compares it - and a backstop stricter than the rule it backs up would silently
     * unpublish a page the doc service accepted.
     */
    @Test
    void writeInto_whenTheNameOnlyDiffersFromAGeneratedOneInCase_thenItIsPublishedAsTheUploadAllowed() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN, page("1-intro", "Whitebox.md", "Whitebox", 1));
        InMemoryStorage storage = new InMemoryStorage().with(set, "1-intro/Whitebox.md", "# Whitebox\n");

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory)).isEqualTo(1);
        }
        assertThat(chapterDirectory.resolve("Whitebox.md")).exists();
    }

    /** An image is not a route, so a picture called after a generated page is still the page's picture. */
    @Test
    void writeInto_whenAnAssetCarriesAGeneratedName_thenItIsStillWritten() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                page("1-intro", "why.md", "Why", 1),
                new CustomPage("1-intro", "index.png", null, 0, true));
        InMemoryStorage storage = new InMemoryStorage()
                .with(set, "1-intro/why.md", "# Why\n")
                .with(set, "1-intro/index.png", "not really a png");

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory))
                    .describedAs("an asset is written and is not counted as a page")
                    .isEqualTo(1);
        }
        assertThat(chapterDirectory.resolve("index.png")).exists();
    }

    /**
     * <b>A body is written through unchanged, so one that cannot be read is not written at all.</b> Decoding
     * it leniently would replace every byte it cannot read with a replacement character and publish a page
     * nobody wrote, silently.
     */
    @Test
    void writeInto_whenThePageIsNotUtf8_thenItIsLeftOutRatherThanRepaired() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                page("1-intro", "why.md", "Why", 1), page("1-intro", "goals.md", "Goals", 2));
        InMemoryStorage storage = new InMemoryStorage()
                .withBytes(set, "1-intro/why.md", new byte[]{'#', ' ', (byte) 0xC3, (byte) 0x28, '\n'})
                .with(set, "1-intro/goals.md", "# Goals\n");

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory))
                    .describedAs("the other page of the chapter is published all the same")
                    .isEqualTo(1);
        }
        assertThat(chapterDirectory.resolve("why.md")).doesNotExist();
        assertThat(chapterDirectory.resolve("goals.md")).exists();
    }

    /** An image is bytes and is never decoded: what it holds is none of this service's business. */
    @Test
    void writeInto_whenAnAssetIsNotText_thenItIsWrittenByteForByte() throws IOException {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                page("1-intro", "why.md", "Why", 1),
                new CustomPage("1-intro", "sketch.png", null, 0, true));
        InMemoryStorage storage = new InMemoryStorage()
                .with(set, "1-intro/why.md", "# Why\n")
                .withBytes(set, "1-intro/sketch.png", png);

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            writer.writeInto(SYSTEM, "1-intro", chapterDirectory);
        }

        assertThat(Files.readAllBytes(chapterDirectory.resolve("sketch.png"))).isEqualTo(png);
    }

    /**
     * <b>A set whose object is gone costs that set, and is only looked for once.</b> A removal deletes the
     * object straight after the row, and so does an upload that replaced one - while a build that has already
     * read the rows may not have opened the bundle yet. Giving up on the set keeps the build from writing
     * half of it, and from asking the storage again for every further page and chapter of it.
     */
    @Test
    void writeInto_whenTheSetsObjectIsGone_thenTheSetIsGivenUpOnAndNotAskedForAgain() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                page("1-intro", "why.md", "Why", 1),
                page("2-constraints", "given.md", "Given", 1));
        // Nothing registered for this set: its object is no longer there.
        InMemoryStorage storage = new InMemoryStorage();

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory.resolve("one"))).isZero();
            assertThat(writer.writeInto(SYSTEM, "2-constraints", chapterDirectory.resolve("two")))
                    .describedAs("the set was given up on, so the second chapter writes nothing either")
                    .isZero();
        }

        assertThat(chapterDirectory.resolve("one").resolve("why.md")).doesNotExist();
        assertThat(chapterDirectory.resolve("two").resolve("given.md")).doesNotExist();
        assertThat(storage.opened())
                .describedAs("and the storage is asked for it once, not once per page")
                .isEqualTo(1);
    }

    /** A set whose rows and whose object disagree costs one page, not the build. */
    @Test
    void writeInto_whenTheBundleDoesNotHoldARecordedFile_thenThatPageIsLeftOut() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                page("1-intro", "why.md", "Why", 1), page("1-intro", "gone.md", "Gone", 2));
        InMemoryStorage storage = new InMemoryStorage().with(set, "1-intro/why.md", "# Why\n");

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory)).isEqualTo(1);
        }
        assertThat(chapterDirectory.resolve("gone.md")).doesNotExist();
    }

    /**
     * The budget is what a <b>set</b> may unpack to, which is what the property says and what the upload
     * applies it to. One writer writes the system's set and every one of its components' and libraries', and
     * a budget shared between them would abandon the later ones over what the earlier ones weigh - and say so
     * naming the wrong set.
     */
    @Test
    void writeInto_thenWhatASetMayUnpackToIsBoundedPerSetAndNotPerBuild() {
        CustomSet ofTheSystem = setOf(1L, SYSTEM, SourceFormat.MARKDOWN, page("1-intro", "a.md", "A", 1));
        CustomSet ofTheComponent = setOf(2L, COMPONENT, SourceFormat.MARKDOWN, page("1-intro", "b.md", "B", 1));
        String sixtyBytes = "x".repeat(60);
        InMemoryStorage storage = new InMemoryStorage()
                .with(ofTheSystem, "1-intro/a.md", sixtyBytes)
                .with(ofTheComponent, "1-intro/b.md", sixtyBytes);
        CustomProperties properties = new CustomProperties();
        properties.setMaxUnpackedSize(org.springframework.util.unit.DataSize.ofBytes(100));

        try (CustomPagesWriter writer = new CustomPagesWriter(
                new CustomDocumentation(List.of(ofTheSystem, ofTheComponent)), storage, new TestTemplate(),
                properties)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory.resolve("system"))).isEqualTo(1);
            assertThat(writer.writeInto(COMPONENT, "1-intro", chapterDirectory.resolve("component")))
                    .describedAs("the second set has its own budget; together they are past it")
                    .isEqualTo(1);
        }
    }

    /**
     * <b>A file is never read past what the set may still unpack to.</b> A ZIP states the size of each entry
     * and the uploader writes that statement, so the check the upload makes is the archive's word; this is
     * the one that measures. Reading an entry whole before measuring it is how a bundle of a few megabytes
     * becomes gigabytes of heap in the service generating the site.
     */
    @Test
    void writeInto_whenAFileInflatesPastTheBudget_thenItIsNotReadWhole() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN, page("1-intro", "bomb.md", "Bomb", 1));
        CountingStorage storage = new CountingStorage(set, "1-intro/bomb.md", 10 * 1024 * 1024);
        CustomProperties properties = new CustomProperties();
        properties.setMaxUnpackedSize(org.springframework.util.unit.DataSize.ofBytes(1024));

        try (CustomPagesWriter writer = new CustomPagesWriter(new CustomDocumentation(List.of(set)), storage,
                new TestTemplate(), properties)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory)).isZero();
        }

        assertThat(storage.bytesRead())
                .describedAs("at most the remaining budget and one byte to know it was passed")
                .isLessThanOrEqualTo(1025);
        assertThat(chapterDirectory.resolve("bomb.md")).doesNotExist();
    }

    /**
     * <b>A file past the per-file bound is left out, not cut off.</b> The set's own budget is the larger of
     * the two by default, so the per-file cap is what a single enormous entry runs into first - and a read
     * that stopped exactly at it would publish a corrupt image, or a page cut mid-character, with nothing
     * saying so.
     */
    @Test
    void writeInto_whenOneFileIsLargerThanThisServiceReads_thenItIsLeftOutRatherThanCutOff() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                new CustomPage("1-intro", "huge.png", null, 0, true),
                page("1-intro", "why.md", "Why", 1));
        // A budget far larger than the file, so it is the per-file bound the read runs into.
        CustomProperties properties = new CustomProperties();
        properties.setMaxUnpackedSize(org.springframework.util.unit.DataSize.ofGigabytes(2));
        CountingStorage storage = new CountingStorage(set, "1-intro/huge.png", 80 * 1024 * 1024);

        try (CustomPagesWriter writer = new CustomPagesWriter(new CustomDocumentation(List.of(set)), storage,
                new TestTemplate(), properties)) {
            writer.writeInto(SYSTEM, "1-intro", chapterDirectory);
        }

        assertThat(chapterDirectory.resolve("huge.png"))
                .describedAs("half a file is worse than none")
                .doesNotExist();
    }

    /** Past its own bound a set is given up on, and the rest of the build goes on without it. */
    @Test
    void writeInto_whenASetUnpacksToMoreThanASetMay_thenItIsAbandonedAndTheBuildGoesOn() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                page("1-intro", "a.md", "A", 1), page("1-intro", "b.md", "B", 2));
        InMemoryStorage storage = new InMemoryStorage()
                .with(set, "1-intro/a.md", "x".repeat(80))
                .with(set, "1-intro/b.md", "y".repeat(80));
        CustomProperties properties = new CustomProperties();
        properties.setMaxUnpackedSize(org.springframework.util.unit.DataSize.ofBytes(100));

        try (CustomPagesWriter writer = new CustomPagesWriter(new CustomDocumentation(List.of(set)), storage,
                new TestTemplate(), properties)) {
            assertThat(writer.writeInto(SYSTEM, "1-intro", chapterDirectory))
                    .describedAs("half a set published beats a part that cannot be built")
                    .isEqualTo(1);
        }
        assertThat(chapterDirectory.resolve("b.md")).doesNotExist();
    }

    /** One bundle per set, opened when the set is first read from and closed with the writer. */
    @Test
    void close_thenEveryBundleItOpenedIsClosed() {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN,
                page("1-intro", "a.md", "A", 1), page("2-constraints", "b.md", "B", 1));
        InMemoryStorage storage = new InMemoryStorage()
                .with(set, "1-intro/a.md", "# A\n")
                .with(set, "2-constraints/b.md", "# B\n");

        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            writer.writeInto(SYSTEM, "1-intro", chapterDirectory.resolve("one"));
            writer.writeInto(SYSTEM, "2-constraints", chapterDirectory.resolve("two"));
        }

        assertThat(storage.opened).describedAs("one request per set and per build").isEqualTo(1);
        assertThat(storage.closed).isEqualTo(1);
    }

    private void writeOnePage(String chapterFolder, String fileName, String content) {
        CustomSet set = setOf(1L, SYSTEM, SourceFormat.MARKDOWN, page(chapterFolder, fileName, "Whatever", 1));
        InMemoryStorage storage =
                new InMemoryStorage().with(set, chapterFolder + "/" + fileName, content);
        try (CustomPagesWriter writer = writerOver(new CustomDocumentation(List.of(set)), storage)) {
            writer.writeInto(SYSTEM, chapterFolder, chapterDirectory);
        }
    }

    private static CustomPagesWriter writerOver(CustomDocumentation documentation,
                                                CustomDocumentationStorage storage) {
        return new CustomPagesWriter(documentation, storage, new TestTemplate(), new CustomProperties());
    }

    private static CustomSet setOf(long id, CustomSubject subject, SourceFormat format, CustomPage... pages) {
        return new CustomSet(id, keyOf(subject, format), 7L, "current/docs/" + id, "abc", 10, PROVENANCE,
                List.of(pages));
    }

    private static CustomSetKey keyOf(CustomSubject subject, SourceFormat format) {
        return new CustomSetKey(subject.site(), subject.kind(), subject.system(), subject.name(), format,
                TestTemplate.ID,
                format == SourceFormat.HTML ? "6-runtime-view" : null,
                format == SourceFormat.HTML ? "reference" : null);
    }

    private static CustomPage page(String chapter, String fileName, String title, int position) {
        return new CustomPage(chapter, fileName, title, position, false);
    }

    /**
     * A template of two chapters that generates one page, so the backstop has something to refuse: the
     * generated name, the landing-page names of the chapter, and the name the chapter folder itself is.
     */
    private static final class TestTemplate implements StructureTemplate {

        private static final String ID = "test-template";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String systemPathSegment() {
            return "test";
        }

        @Override
        public String systemLabel() {
            return "Test";
        }

        @Override
        public String componentPathSegment() {
            return "test-component";
        }

        @Override
        public String componentLabel() {
            return "Test Component";
        }

        @Override
        public String libraryPathSegment() {
            return "test-library";
        }

        @Override
        public String libraryLabel() {
            return "Test Library";
        }

        @Override
        public List<StructureChapter> chapters() {
            return List.of(new StructureChapter(1, "1-intro", "Intro"),
                    new StructureChapter(2, "2-constraints", "Constraints"),
                    new StructureChapter(5, "5-building-block-view", "Building Block View"));
        }

        @Override
        public Set<String> allowedFileExtensions() {
            return Set.of("md", "png");
        }

        @Override
        public Set<String> generatedNames(StructureChapter chapter, SubjectKind subject) {
            return chapter != null && "1-intro".equals(chapter.folder()) ? Set.of("whitebox") : Set.of();
        }

        @Override
        public void writeSystem(SystemDocumentation system, GenerationContext context, Path systemDirectory) {
            // Not a test about a template's own pages.
        }
    }

    /**
     * One file that inflates without end, counting what was actually read of it - the measurement a bound on
     * the read has to be checked by.
     */
    private static final class CountingStorage implements CustomDocumentationStorage {

        private final CustomSet set;
        private final String path;
        private final int length;
        private int bytesRead;

        CountingStorage(CustomSet set, String path, int length) {
            this.set = set;
            this.path = path;
            this.length = length;
        }

        int bytesRead() {
            return bytesRead;
        }

        @Override
        public String promote(StoredBundle stored, CustomSetKey key, long revision, int attempt) {
            throw new UnsupportedOperationException("nothing is uploaded in this test");
        }

        @Override
        public Optional<OpenedBundle> open(CustomSet opened) {
            return Optional.of(new OpenedBundle() {

                @Override
                public Optional<InputStream> read(String read) {
                    if (!read.equals(path) || !opened.objectKey().equals(set.objectKey())) {
                        return Optional.empty();
                    }
                    return Optional.of(new InputStream() {

                        private int given;

                        @Override
                        public int read() {
                            if (given >= length) {
                                return -1;
                            }
                            given++;
                            bytesRead++;
                            return 'x';
                        }
                    });
                }

                @Override
                public void close() {
                    // nothing to release
                }
            });
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException("nothing is removed in this test");
        }

        @Override
        public List<String> listWrittenBefore(Instant writtenBefore) {
            return List.of();
        }
    }

    /** The sets of a build, in memory, counting how often a bundle was opened and closed. */
    private static final class InMemoryStorage implements CustomDocumentationStorage {

        private final Map<String, Map<String, byte[]>> bundles = new LinkedHashMap<>();
        private int opened;
        private int closed;

        /** How often the storage was asked for a bundle, whether or not it had one. */
        int opened() {
            return opened;
        }

        InMemoryStorage with(CustomSet set, String path, String content) {
            return withBytes(set, path, content.getBytes(StandardCharsets.UTF_8));
        }

        InMemoryStorage withBytes(CustomSet set, String path, byte[] content) {
            bundles.computeIfAbsent(set.objectKey(), key -> new LinkedHashMap<>()).put(path, content);
            return this;
        }

        @Override
        public String promote(StoredBundle stored, CustomSetKey key, long revision, int attempt) {
            throw new UnsupportedOperationException("nothing is uploaded in this test");
        }

        @Override
        public Optional<OpenedBundle> open(CustomSet set) {
            opened++;
            if (!bundles.containsKey(set.objectKey())) {
                return Optional.empty();
            }
            Map<String, byte[]> files = bundles.getOrDefault(set.objectKey(), Map.of());
            return Optional.of(new OpenedBundle() {

                @Override
                public Optional<InputStream> read(String path) {
                    byte[] content = files.get(path);
                    return content == null
                            ? Optional.empty()
                            : Optional.of(new ByteArrayInputStream(content));
                }

                @Override
                public void close() {
                    closed++;
                }
            });
        }

        @Override
        public void delete(String objectKey) {
            bundles.remove(objectKey);
        }

        @Override
        public List<String> listWrittenBefore(Instant writtenBefore) {
            return new ArrayList<>(bundles.keySet());
        }
    }
}
