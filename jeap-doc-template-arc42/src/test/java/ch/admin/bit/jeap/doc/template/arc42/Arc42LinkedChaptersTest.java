package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Every chapter a landing page links to has a landing page of its own.</b>
 * <p>
 * The site is generated with {@code onBrokenLinks: 'throw'}, so a landing page that lists a chapter with no
 * index page fails the whole build of that part - twenty minutes in, naming a route. That is a browser test's
 * worth of feedback for a mistake a directory listing can see, so it is checked here.
 * <p>
 * It is written against the shapes where the two halves are written by different code and can therefore
 * disagree: a subject the architecture model does not hold, a library, and a chapter a team filled.
 */
class Arc42LinkedChaptersTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-09-11T10:00:00Z");

    /** A Markdown link to a chapter of a tree, absolute or relative. */
    private static final Pattern CHAPTER_LINK = Pattern.compile("\\]\\((\\./)?([^)]*?)/\\)");

    private final Arc42Template template = new Arc42Template();

    @TempDir
    Path content;

    private static DocumentedSystem orders() {
        return new DocumentedSystem("ORDERS", "orders", "Takes orders", List.of(), null,
                List.of(new DocumentedComponent("orders-intake", "orders-intake", "Takes them in",
                        ComponentType.BACKEND_SERVICE, null, "DEPLOYMENT_LOG", null, List.of(), null, null,
                        null)),
                List.of(), List.of());
    }

    private GenerationContext context(ArchitectureModel model) {
        return new GenerationContext(model, "prod", "https://archrepo.example", GENERATED_AT, GENERATED_AT,
                new DiagramLimits(100, 4, 40, 100, 200), "/");
    }

    private Path write(SystemDocumentation documented, ArchitectureModel model) throws IOException {
        Path directory = content.resolve("systems").resolve(documented.slug());
        template.writeSystem(documented, context(model), directory);
        return directory;
    }

    /**
     * Every link of the form {@code …/something/} on a generated landing page points at a directory that has
     * an {@code index.md}, or at a page that exists.
     */
    private void assertEveryLinkedChapterHasALandingPage(Path tree) throws IOException {
        List<String> broken = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tree)) {
            for (Path page : files.filter(file -> file.getFileName().toString().equals("index.md")).toList()) {
                Matcher links = CHAPTER_LINK.matcher(Files.readString(page));
                while (links.find()) {
                    String target = links.group(2);
                    if (target.startsWith("http") || target.isBlank()) {
                        continue;
                    }
                    // A relative link resolves beside the page; an absolute one is not this test's to
                    // resolve, and the tree tests cover those.
                    if (links.group(1) == null) {
                        continue;
                    }
                    // A link carries the chapter's URL segment and the directory carries its folder, which
                    // differ by the number prefix - so the template resolves one to the other.
                    Path directory = page.getParent().resolve(folderOf(target));
                    if (!Files.exists(directory.resolve("index.md"))) {
                        broken.add(tree.relativize(page) + " -> ./" + target + "/");
                    }
                }
            }
        }
        assertThat(broken).describedAs("links to a chapter with no landing page, which fail the site build")
                .isEmpty();
    }

    /** The folder a chapter's URL segment is written under, or the segment itself where it is no chapter. */
    private String folderOf(String urlSegment) {
        return template.chapters().stream()
                .filter(chapter -> chapter.urlSegment().equals(urlSegment))
                .map(StructureChapter::folder)
                .findFirst()
                .orElse(urlSegment);
    }

    @Test
    void aSystemTheModelDoesNotHold_linksNoChapterThatHasNoLandingPage() throws IOException {
        SystemDocumentation documented = Documented.uploadsOnly("catalog",
                Map.of("12-glossary", List.of("terms.md")));

        assertEveryLinkedChapterHasALandingPage(write(documented, new ArchitectureModel(List.of())));
    }

    /**
     * The case the browser suite found: chapter 1 has a generated page and no index of its own, because
     * nothing was uploaded into it.
     */
    @Test
    void aSystemTheModelDoesNotHold_hasALandingPageInChapterOne() throws IOException {
        SystemDocumentation documented = Documented.uploadsOnly("catalog",
                Map.of("12-glossary", List.of("terms.md")));

        Path tree = write(documented, new ArchitectureModel(List.of()));

        assertThat(tree.resolve(Arc42Template.SYSTEM_SEGMENT).resolve("1-intro").resolve("index.md"))
                .describedAs("the structure landing page lists chapter 1, so it has to be a page")
                .exists();
    }

    @Test
    void aLibrary_linksNoChapterThatHasNoLandingPage() throws IOException {
        DocumentedSystem system = orders();
        SystemDocumentation documented = Documented.withALibrary(system, "orders-client",
                Map.of("12-glossary", List.of("terms.md")));

        assertEveryLinkedChapterHasALandingPage(write(documented, new ArchitectureModel(List.of(system))));
    }

    @Test
    void aLibrary_hasALandingPageInChapterOne() throws IOException {
        DocumentedSystem system = orders();
        SystemDocumentation documented = Documented.withALibrary(system, "orders-client",
                Map.of("12-glossary", List.of("terms.md")));

        Path tree = write(documented, new ArchitectureModel(List.of(system)));

        assertThat(tree.resolve(Arc42Template.SYSTEM_SEGMENT).resolve("5-building-block-view")
                        .resolve("libraries").resolve("orders-client")
                        .resolve(Arc42Template.LIBRARY_SEGMENT).resolve("1-intro").resolve("index.md"))
                .exists();
    }

    @Test
    void aComponentTheModelDoesNotHold_linksNoChapterThatHasNoLandingPage() throws IOException {
        DocumentedSystem system = orders();
        SystemDocumentation documented = Documented.withADocumentedComponent(system, "orders-reporting",
                Map.of("2-constraints", List.of("given.md")));

        assertEveryLinkedChapterHasALandingPage(write(documented, new ArchitectureModel(List.of(system))));
    }

    @Test
    void aSystemTheModelHolds_withUploadedChapters_linksNoChapterThatHasNoLandingPage() throws IOException {
        DocumentedSystem system = orders();
        SystemDocumentation documented = Documented.withUploads(system,
                Map.of("12-glossary", List.of("terms.md"), "10-quality-requirements", List.of("goals.md")));

        assertEveryLinkedChapterHasALandingPage(write(documented, new ArchitectureModel(List.of(system))));
    }

    /**
     * The model has no relation of this system, so chapter 3 is not generated. A team that uploads chapter 3
     * still gets it, with a landing page of its own.
     */
    @Test
    void aSystemWithNoRelations_withAnUploadedChapterThree_getsThatChapter() throws IOException {
        DocumentedSystem system = orders();
        SystemDocumentation documented = Documented.withUploads(system,
                Map.of("3-context-and-scope", List.of("neighbours.md")));

        Path tree = write(documented, new ArchitectureModel(List.of(system)));

        Path chapter = tree.resolve(Arc42Template.SYSTEM_SEGMENT).resolve("3-context-and-scope");
        assertThat(chapter.resolve("index.md")).exists();
        assertThat(chapter.resolve("system-context-view.md")).doesNotExist();
        assertThat(Files.readString(tree.resolve(Arc42Template.SYSTEM_SEGMENT).resolve("index.md")))
                .contains("3. Context and Scope");
        assertEveryLinkedChapterHasALandingPage(tree);
    }
}
