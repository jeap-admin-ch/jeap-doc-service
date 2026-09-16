package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tree of a library: the group beside the components, the pages generated into it, and the chapters the
 * team wrote.
 * <p>
 * It walks what was written, the way {@code Arc42SystemTreeTest} does, so a page added without being reserved
 * fails here rather than on a duplicate route twenty minutes into a build.
 */
class Arc42LibraryTreeTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-09-11T09:00:00Z");
    private static final String LIBRARY = "orders-client";

    private final Arc42Template template = new Arc42Template();

    @TempDir
    Path content;

    private Path systemDirectory;

    @BeforeEach
    void setUp() {
        systemDirectory = content.resolve("systems").resolve("orders");
    }

    private static DocumentedSystem orders() {
        return new DocumentedSystem("ORDERS", "orders", "Takes orders", List.of(), null, List.of(),
                List.of(), List.of());
    }

    private GenerationContext contextOf(DocumentedSystem system) {
        return new GenerationContext(new ArchitectureModel(List.of(system)), "prod",
                "https://archrepo.example", GENERATED_AT, GENERATED_AT,
                new DiagramLimits(100, 4, 40, 100, 200, 40, 20), "/");
    }

    private void writeWith(Map<String, List<String>> pagesByChapter) throws IOException {
        DocumentedSystem system = orders();
        SystemDocumentation documented = Documented.withALibrary(system, LIBRARY, pagesByChapter);
        template.writeSystem(documented, contextOf(system), systemDirectory);
    }

    private Path libraryTree() {
        return systemDirectory.resolve(Arc42Template.SYSTEM_SEGMENT).resolve("5-building-block-view")
                .resolve("libraries").resolve(LIBRARY);
    }

    @Test
    void aLibrary_isATreeBesideTheComponents() throws IOException {
        writeWith(Map.of("2-constraints", List.of("what-was-given.md")));

        assertThat(libraryTree()).isDirectory();
        assertThat(libraryTree().resolve("index.md")).exists();
        assertThat(libraryTree().resolve(Arc42Template.LIBRARY_SEGMENT).resolve("index.md")).exists();
    }

    @Test
    void theGroup_hasItsCategoryAndAnIndexPage() throws IOException {
        writeWith(Map.of("2-constraints", List.of("what-was-given.md")));

        Path group = libraryTree().getParent();
        assertThat(group.resolve("_category_.json")).exists();
        assertThat(Files.readString(group.resolve("_category_.json")))
                .describedAs("beside the components, after them - and its own position, because two groups "
                             + "of one position are ordered by their folder names instead")
                .contains("\"label\": \"Libraries\"").contains("\"position\": 3");
        assertThat(Arc42LibraryPages.LIBRARIES_POSITION)
                .describedAs("the components are at two, the events at four and the commands at five")
                .isEqualTo(3)
                .isNotEqualTo(Arc42SystemPages.EVENTS_POSITION)
                .isNotEqualTo(Arc42SystemPages.COMMANDS_POSITION);
        assertThat(group.resolve("index.md")).describedAs("a group a reader can read, not only expand")
                .exists();
        assertThat(Files.readString(group.resolve("index.md"))).contains(LIBRARY);
    }

    /**
     * <b>No architecture model holds a library</b>, so none of its pages may say it came from one. The site
     * template renders the provenance from {@code doc_source}, and {@code archrepo} there tells a reader the
     * page was generated from a model that has never heard of the thing it describes.
     */
    @Test
    void everyGeneratedPage_saysItDidNotComeFromAnArchitectureModel() throws IOException {
        writeWith(Map.of("2-constraints", List.of("what-was-given.md")));

        Path structure = libraryTree().resolve(Arc42Template.LIBRARY_SEGMENT);
        try (var files = Files.walk(structure)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".md"))
                    .filter(file -> !isUploaded(file))
                    .forEach(file -> {
                        String page = readOrThrow(file);
                        assertThat(page)
                                .describedAs("%s says it came from the architecture model", file)
                                .doesNotContain("doc_source: \"archrepo\"");
                        assertThat(page).contains("doc_source: \"doc-service\"");
                    });
        }
    }

    private static String readOrThrow(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void theLibrarysOwnStructure_startsClosed() throws IOException {
        writeWith(Map.of("2-constraints", List.of("what-was-given.md")));

        assertThat(Files.readString(libraryTree().resolve("_category_.json"))).doesNotContain("collapsed");
        assertThat(Files.readString(
                libraryTree().resolve(Arc42Template.LIBRARY_SEGMENT).resolve("_category_.json")))
                .describedAs("a category with no collapsed key is closed until a reader opens it")
                .doesNotContain("collapsed");
    }

    @Test
    void theOverviewPage_saysWhatTheUploadSaid() throws IOException {
        writeWith(Map.of("2-constraints", List.of("what-was-given.md")));

        Path overview = libraryTree().resolve(Arc42Template.LIBRARY_SEGMENT).resolve("1-intro")
                .resolve("library-overview.md");
        assertThat(overview).exists();
        assertThat(Files.readString(overview)).contains(LIBRARY).contains("docs").contains("main")
                .describedAs("when it was uploaded, as a reader reads it")
                .contains("| Uploaded | " + DisplayTime.of(Instant.EPOCH) + " |")
                .doesNotContain("1970-01-01T00:00:00Z");
    }

    @Test
    void aChapterTheTeamWrote_getsItsFolderAndAnIndexPage() throws IOException {
        writeWith(Map.of("2-constraints", List.of("what-was-given.md"),
                "12-glossary", List.of("terms.md")));

        Path structure = libraryTree().resolve(Arc42Template.LIBRARY_SEGMENT);
        assertThat(structure.resolve("2-constraints").resolve("_category_.json")).exists();
        assertThat(structure.resolve("12-glossary").resolve("_category_.json")).exists();
    }

    @Test
    void theStructureLandingPage_listsTheChaptersThatExist() throws IOException {
        writeWith(Map.of("12-glossary", List.of("terms.md")));

        String landing = Files.readString(
                libraryTree().resolve(Arc42Template.LIBRARY_SEGMENT).resolve("index.md"));

        assertThat(landing).contains("Glossary").contains("Introduction and Goals")
                .describedAs("a chapter nobody wrote is not linked, or the build fails on it")
                .doesNotContain("Deployment View");
    }

    @Test
    void theSystemsStructureLandingPage_listsTheLibrary() throws IOException {
        writeWith(Map.of("12-glossary", List.of("terms.md")));

        String landing = Files.readString(
                systemDirectory.resolve(Arc42Template.SYSTEM_SEGMENT).resolve("index.md"));

        assertThat(landing).contains("Libraries").contains(LIBRARY);
    }

    @Test
    void aSystemWithNoLibrary_getsNoGroup() throws IOException {
        DocumentedSystem system = orders();
        template.writeSystem(Documented.of(system), contextOf(system), systemDirectory);

        assertThat(systemDirectory.resolve(Arc42Template.SYSTEM_SEGMENT).resolve("5-building-block-view")
                .resolve("libraries")).doesNotExist();
    }

    /**
     * <b>The anti-drift test.</b> Every <i>generated</i> file of a library's tree is either reserved by the
     * template or one the domain reserves everywhere. A page added without being declared fails here, in the
     * module that added it.
     * <p>
     * An uploaded page is not one of those and is skipped: what it may be called was decided when its set was
     * received, and the tree now really carries them - the writer this test runs against used to answer zero
     * and write nothing.
     */
    @Test
    void everyGeneratedFile_isOneTheTemplateReserves() throws IOException {
        writeWith(Map.of("2-constraints", List.of("what-was-given.md")));

        Path structure = libraryTree().resolve(Arc42Template.LIBRARY_SEGMENT);
        try (var files = Files.walk(structure)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".md"))
                    .filter(file -> !isUploaded(file))
                    .forEach(file -> {
                        String name = file.getFileName().toString().replace(".md", "");
                        String chapter = file.getParent().getFileName().toString();
                        boolean reserved = "index".equals(name)
                                           || template.chapterOfFolder(chapter)
                                                   .map(found -> template.generatedNames(found,
                                                           ch.admin.bit.jeap.doc.domain.upload.SubjectKind
                                                                   .LIBRARY).contains(name))
                                                   .orElse(false);
                        assertThat(reserved)
                                .describedAs("%s is generated into %s and nothing reserves it", name, chapter)
                                .isTrue();
                    });
        }
    }

    /** What a page says it is: {@code custom} is a page a team uploaded, whatever it is called. */
    private static boolean isUploaded(Path file) {
        try {
            return Files.readString(file).contains("doc_status: custom");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
