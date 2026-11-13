package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.custom.ChapterOrder;
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomPages;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.custom.Microsite;
import ch.admin.bit.jeap.doc.domain.custom.OrderHint;
import ch.admin.bit.jeap.doc.domain.custom.PageGroup;
import ch.admin.bit.jeap.doc.domain.custom.UploadedFrontMatter;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.template.arc42.Arc42Template;

import java.io.IOException;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A system documented entirely by uploads, for the browser tests.
 * <p>
 * The pages are written the way the site generator writes them - the body as it was uploaded, the front
 * matter generated - so what a browser sees here is what a reader gets from a real upload. What is skipped is
 * the endpoint and the object storage, which {@code CustomDocsGenerationIT} covers end to end.
 * <p>
 * <b>Every position comes from the real {@link ChapterOrder}</b>, built from the same items
 * {@code CustomPagesWriter} builds it from. A browser test of the order then checks what the service would
 * publish, not a numbering of this class's own.
 */
final class UploadedDocumentation {

    static final String REPOSITORY = "ssh://git@example.ch/catalog/catalog-docs.git";
    static final String REVISION = "beef1234";
    static final String LIBRARY_VERSION = "4.2.0";

    /** A word only the uploaded glossary page carries, so a test can prove where the page came from. */
    static final String GLOSSARY_WORD = "backorder";

    /** The file of the page below, whose name is its route in the constraints chapter. */
    static final String RAW_HTML_PAGE = "raw-html";

    /** Where the uploaded microsite is embedded, and what names it - see {@code MicrositeBrowserIT}. */
    static final String MICROSITE_LOCATION = "2-constraints";
    static final String MICROSITE_TOPIC = "reference";
    static final String MICROSITE_LABEL = "Configuration Reference";

    /**
     * An uploaded page carrying raw HTML, to be shown as text and never applied.
     * <p>
     * Uploaded Markdown is read as CommonMark, and the tags below would otherwise reach the reader as markup:
     * a script on the site's origin, a stylesheet over the whole page and a frame of the service itself. What
     * stops them is {@code plugins/remark-escape-raw-html} in the site template, and a browser is the only
     * place that says so - the build is green either way.
     */
    private static final String RAW_HTML_BODY = """
            <script>window.MARKER_SCRIPT = 1</script>

            <style>body { outline: 7px solid red }</style>

            <iframe id="injected-frame" src="/"></iframe>

            :::note

            An admonition still renders.

            :::

            :::details[Uploaded <b>fold</b>]

            Folded by the team.

            :::

            ```plantuml
            @startuml
            Alice -> Bob: hello
            @enduml
            ```
            """;

    /** The chapter a group is uploaded into - see {@code GroupBrowserIT}. */
    static final String GROUP_CHAPTER = "9-architecture-decision-records";

    /** The group's own folder, and the label its uploaded landing page names. */
    static final String GROUP_FOLDER = "decisions";
    static final String GROUP_LABEL = "Key decisions";

    /** The group's own pages, titled so the second comes out first if ordering ever fell back to the title. */
    static final String GROUP_PAGE_ONE = "automated-testing";
    static final String GROUP_PAGE_ONE_TITLE = "Automated testing";
    static final String GROUP_PAGE_TWO = "message-contracts";
    static final String GROUP_PAGE_TWO_TITLE = "Zzz message contract versioning";

    /**
     * The chapter where two pages are placed around a generated item - see {@code SiblingPlacementBrowserIT}.
     * For a system no architecture model holds, the only thing generated into it is the libraries group.
     */
    static final String PLACEMENT_CHAPTER = "5-building-block-view";

    /** The label of that generated group. */
    static final String GENERATED_SIBLING_LABEL = "Libraries";

    /** A page placed before the libraries group by its own {@code sidebar_before}. Its title sorts last. */
    static final String PLACED_PAGE_TITLE = "Read this before the libraries";

    /** A page with no placement. Its title sorts first, but as a free item it still comes after the group. */
    static final String FREE_PAGE_TITLE = "Afterword";

    /** Their body. It avoids the words the search tests count, so these two pages change none of their counts. */
    private static final String PLACEMENT_BODY = "Placed by its own front matter.";

    /** An uploaded page with a table of 18 terms, in the constraints chapter - see {@code TableControlsBrowserIT}. */
    static final String TERMS_PAGE = "terms";

    private static final String TERMS_BODY = """
            | Term | Meaning | Since |
            | ---- | ------- | ----- |
            | Backorder | An order line waiting for stock | 1.10 |
            | Carrier | The company that moves a parcel | 1.2 |
            | Consignment | Parcels travelling together | 1.9 |
            | Customs declaration | What the [customs office](https://www.bazg.admin.ch) receives | 1.10 |
            | Dispatch | Handing a parcel to a carrier | 1.0 |
            | Fulfilment | Picking, packing and dispatch of an order | 1.0 |
            | Invoice | The bill for an order | 1.1 |
            | Lot | Goods produced together | 1.4 |
            | Manifest | The list of parcels of a pickup | 1.9 |
            | Order | What a customer buys at once | 1.0 |
            | Parcel | One box on its way | 1.0 |
            | Pickup | A carrier collecting parcels | 1.3 |
            | Refund | Money returned for an order | 1.5 |
            | Return | Goods sent back by a customer | 1.5 |
            | SKU | A stock keeping unit | 1.0 |
            | Stock reservation | Stock held for an order | 1.6 |
            | Tariff number | The customs code of a good | 1.10 |
            | Warehouse | Where the stock is kept | 1.0 |
            """;

    private static final Instant UPLOADED_AT = Instant.parse("2026-09-01T07:15:00Z");

    /** A page below the entry point: what a deep link and a search hit inside a microsite arrive at. */
    static final String NESTED_PAGE = "pages/properties.html";

    static final String NESTED_HEADING = "Every property, one by one";

    /** A word that is only inside the microsite, so finding it proves its content was indexed. */
    static final String ONLY_INSIDE_THE_MICROSITE = "idempotency";

    /** What the nested page's own stylesheet paints its heading, to say the frame loaded it too. */
    static final String NESTED_COLOUR = "rgb(0, 100, 0)";

    static final String ENTRY_POINT_HEADING = "Every property of this service";

    /** The parameters of the microsite upload, as a build's doc workflow sends them. */
    static Map<String, String> micrositeUpload(String system) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "system-docs");
        parameters.put("system", system);
        parameters.put("template", "arc42");
        parameters.put("source-format", "html");
        parameters.put("location", MICROSITE_LOCATION);
        parameters.put("topic", MICROSITE_TOPIC);
        parameters.put("label", MICROSITE_LABEL);
        parameters.put("source-repository", REPOSITORY);
        parameters.put("source-revision", REVISION);
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-09-01T07:15:00Z");
        return parameters;
    }

    /**
     * The microsite the browser tests drive: an entry point that reads storage while it loads, which is what
     * the shim is for, and a page below it with a stylesheet of its own, which is what a deep link and a
     * search hit have to arrive at.
     */
    static byte[] micrositeBundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "index.html", entryPoint());
            writeEntry(zip, NESTED_PAGE, nestedPage());
            writeEntry(zip, "pages/style.css", "h1 { color: " + NESTED_COLOUR + " }\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** The page below the entry point, which names its stylesheet relative to itself. */
    private static String nestedPage() {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Properties</title>
                <link rel="stylesheet" href="style.css"></head>
                <body><h1>%s</h1>
                <p>Every request carries an %s key, and a repeated one is answered from the log.</p>
                </body></html>
                """.formatted(NESTED_HEADING, ONLY_INSIDE_THE_MICROSITE);
    }

    /** A microsite that reads storage while it loads, which is what the shim is for. */
    private static String entryPoint() {
        return ("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Configuration</title></head>
                <body>
                <h1>%s</h1>
                <script>
                  // What an application does while it loads, and what a sandboxed document may not do
                  // without the shim: reading this throws SecurityError.
                  window.STORAGE_WORKED = false;
                  try {
                    localStorage.setItem('probe', 'yes');
                    window.STORAGE_WORKED = localStorage.getItem('probe') === 'yes';
                  } catch (e) {
                    window.STORAGE_WORKED = false;
                  }
                  window.ORIGIN = String(window.origin);
                  try {
                    window.REACHED_PARENT = Boolean(parent.location.href);
                  } catch (e) {
                    window.REACHED_PARENT = false;
                  }
                </script>
                </body></html>
                """).formatted(ENTRY_POINT_HEADING);
    }

    private UploadedDocumentation() {
    }

    /**
     * The system, its glossary and constraints chapters, and one library with a chapter of its own.
     * <p>
     * The titles are deliberately the reverse of the file names: a chapter whose pages come out in title
     * order can only have been ordered by the service, since Docusaurus would have used the file names.
     */
    static SystemDocumentation of(String system, String library) {
        Map<CustomSubject, Map<String, List<Page>>> pages = new LinkedHashMap<>();
        pages.put(new CustomSubject(Site.DEFAULT_SITE, SubjectKind.SYSTEM, system, null), Map.of(
                "12-glossary", List.of(
                        new Page("zzz-first.md", "A " + GLOSSARY_WORD + " and what it is"),
                        new Page("aaa-second.md", "Z is for the last word")),
                "2-constraints", List.of(new Page("given.md", "What was given"),
                        new Page(RAW_HTML_PAGE + ".md", "Raw HTML as it was uploaded", RAW_HTML_BODY),
                        new Page(TERMS_PAGE + ".md", "Terms of the trade", TERMS_BODY)),
                GROUP_CHAPTER, List.of(
                        new Page(GROUP_FOLDER + "/index.md", GROUP_LABEL),
                        new Page(GROUP_FOLDER + "/" + GROUP_PAGE_ONE + ".md", GROUP_PAGE_ONE_TITLE),
                        new Page(GROUP_FOLDER + "/" + GROUP_PAGE_TWO + ".md", GROUP_PAGE_TWO_TITLE)),
                PLACEMENT_CHAPTER, List.of(
                        new Page("before-the-libraries.md", PLACED_PAGE_TITLE, PLACEMENT_BODY,
                                new OrderHint(null, OrderHint.Direction.BEFORE,
                                        DocumentationPaths.LIBRARIES_SEGMENT)),
                        new Page("afterword.md", FREE_PAGE_TITLE, PLACEMENT_BODY, OrderHint.NONE))));
        pages.put(new CustomSubject(Site.DEFAULT_SITE, SubjectKind.LIBRARY, system, library), Map.of(
                "12-glossary", List.of(new Page("terms.md", "The terms of the client"))));
        List<CustomSet> sets = new ArrayList<>(setsOf(pages));
        sets.add(micrositeOf(system));
        CustomDocumentation documentation = new CustomDocumentation(sets);
        return SystemDocumentation.ofUploadsOnly(Site.DEFAULT_SITE, system, documentation,
                new WritesThePages(pages, documentation));
    }

    private static List<CustomSet> setsOf(Map<CustomSubject, Map<String, List<Page>>> pages) {
        List<CustomSet> sets = new ArrayList<>();
        long revision = 1;
        for (Map.Entry<CustomSubject, Map<String, List<Page>>> subject : pages.entrySet()) {
            List<CustomPage> files = new ArrayList<>();
            subject.getValue().forEach((chapter, inChapter) -> {
                List<Page> sorted = inChapter.stream()
                        .sorted(java.util.Comparator.comparing(Page::title)).toList();
                for (int i = 0; i < sorted.size(); i++) {
                    files.add(new CustomPage(chapter, sorted.get(i).fileName(), sorted.get(i).title(),
                            i + 1, false, sorted.get(i).hint()));
                }
            });
            CustomSubject key = subject.getKey();
            sets.add(new CustomSet(revision, new CustomSetKey(key.site(), key.kind(), key.system(),
                    key.name(), SourceFormat.MARKDOWN, "arc42", null, null), null, revision,
                    "current/docs/browser/" + revision + "/bundle.zip", "abc", 100,
                    new CustomProvenance(REPOSITORY, "main", REVISION, UPLOADED_AT,
                            key.kind() == SubjectKind.LIBRARY ? LIBRARY_VERSION : null, UPLOADED_AT),
                    files));
            revision++;
        }
        return sets;
    }

    /**
     * The HTML set of the system, whose files a browser test uploads for real. Its row is what puts the
     * microsite into the chapter; the files are served live from the bucket under the prefix it names.
     */
    private static CustomSet micrositeOf(String system) {
        CustomSetKey key = new CustomSetKey(Site.DEFAULT_SITE, SubjectKind.SYSTEM, system, null,
                SourceFormat.HTML, "arc42", MICROSITE_LOCATION, MICROSITE_TOPIC);
        return new CustomSet(99L, key, MICROSITE_LABEL, 99L, "current/docs/browser/99/1/files/", "abc",
                100, new CustomProvenance(REPOSITORY, "main", REVISION, UPLOADED_AT, null, UPLOADED_AT),
                List.of());
    }

    /**
     * Writes the pages the way the site generator does: the body as uploaded, the front matter generated, and
     * each position from the chapter's one {@link ChapterOrder}.
     */
    private record WritesThePages(Map<CustomSubject, Map<String, List<Page>>> pages,
                                  CustomDocumentation documentation) implements CustomPages {

        private static final StructureTemplate TEMPLATE = new Arc42Template();

        /**
         * The page that frames the microsite, written the way {@code CustomPagesWriter} writes it: what a
         * browser has to drive is the frame the site template builds out of this front matter.
         */
        @Override
        public int writeMicrositesInto(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
            List<Microsite> microsites = documentation.micrositesOf(subject, TEMPLATE.id(), chapterFolder);
            if (microsites.isEmpty()) {
                return 0;
            }
            ChapterOrder order = orderOf(subject, chapterFolder, chapterDirectory);
            for (Microsite microsite : microsites) {
                writeMicrosite(subject, microsite, order.positionOf(microsite), chapterDirectory);
            }
            return microsites.size();
        }

        private static void writeMicrosite(CustomSubject subject, Microsite microsite, int position,
                                           Path chapterDirectory) {
            Map<String, Object> generated = UploadedFrontMatter.keys();
            generated.put("title", MICROSITE_LABEL);
            generated.put("sidebar_position", position);
            generated.put("slug", "microsites/" + MICROSITE_TOPIC);
            generated.put("hide_table_of_contents", true);
            generated.put("className", "doc-microsite-page");
            generated.put("doc_status", "custom");
            generated.put("doc_source", "upload");
            generated.put("doc_microsite_url", "/microsites/" + subject.system() + "/arc42/"
                                               + MICROSITE_LOCATION + "/" + MICROSITE_TOPIC + "/");
            generated.put("doc_microsite_label", MICROSITE_LABEL);
            generated.put("doc_source_repository", REPOSITORY);
            generated.put("doc_source_ref", "main");
            generated.put("doc_source_revision", REVISION);
            generated.put("doc_uploaded_at", UPLOADED_AT.toString());
            generated.put("doc_uploaded_at_display", DisplayTime.of(UPLOADED_AT));
            try {
                Files.createDirectories(chapterDirectory);
                Files.writeString(chapterDirectory.resolve(microsite.fileName()),
                        UploadedFrontMatter.rewritten("Published by the team that owns it.\n", generated),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public int writeInto(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
            Optional<CustomSet> set = documentation.setOf(subject);
            if (set.isEmpty()) {
                return 0;
            }
            ChapterOrder order = orderOf(subject, chapterFolder, chapterDirectory);
            int written = 0;
            for (CustomPage page : set.get().pagesOf(chapterFolder)) {
                write(pageOf(subject, page), order.positionOf(page), chapterDirectory, false);
                written++;
            }
            for (PageGroup group : set.get().groupsOf(chapterFolder)) {
                written += writeGroup(subject, group, order.positionOf(group), chapterDirectory);
            }
            return written;
        }

        /**
         * The order {@code CustomPagesWriter} numbers a chapter from: the generated siblings already on disk,
         * the pages, the microsites and the groups.
         */
        private ChapterOrder orderOf(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
            Optional<CustomSet> set = documentation.setOf(subject);
            return ChapterOrder.of(presentSiblingsOf(chapterFolder, subject.kind(), chapterDirectory),
                    set.map(found -> found.pagesOf(chapterFolder)).orElseGet(List::of),
                    documentation.micrositesOf(subject, TEMPLATE.id(), chapterFolder),
                    set.map(found -> found.groupsOf(chapterFolder)).orElseGet(List::of));
        }

        /** The generated siblings written into this chapter, each at the position the template gave it. */
        private static List<ChapterOrder.PresentSibling> presentSiblingsOf(String chapterFolder, SubjectKind kind,
                                                                            Path chapterDirectory) {
            Optional<StructureChapter> chapter = TEMPLATE.chapterOfFolder(chapterFolder);
            if (chapter.isEmpty()) {
                return List.of();
            }
            List<ChapterOrder.PresentSibling> present = new ArrayList<>();
            for (String name : TEMPLATE.generatedSiblings(chapter.get(), kind)) {
                if (Files.exists(chapterDirectory.resolve(name + "." + DocumentationPaths.MARKDOWN_EXTENSION))
                    || Files.isDirectory(chapterDirectory.resolve(name))) {
                    present.add(new ChapterOrder.PresentSibling(name,
                            TEMPLATE.siblingPosition(chapter.get(), kind, name)));
                }
            }
            return present;
        }

        /** The uploaded page a stored page row stands for. */
        private Page pageOf(CustomSubject subject, CustomPage page) {
            return pages.get(subject).get(page.chapter()).stream()
                    .filter(uploaded -> uploaded.fileName().equals(page.fileName()))
                    .findFirst().orElseThrow();
        }

        /**
         * A group written the way {@code CustomPagesWriter} really writes one: its {@code _category_.json}, its
         * own pages in their own order, and its landing page. The fixture's one group uploads its landing page.
         */
        private int writeGroup(CustomSubject subject, PageGroup group, int position, Path chapterDirectory) {
            Page landing = pageOf(subject, group.landingPage().orElseThrow());
            Path groupDirectory = chapterDirectory.resolve(group.folder());
            try {
                Files.createDirectories(groupDirectory);
                Files.writeString(groupDirectory.resolve("_category_.json"), """
                        {
                          "label": "%s",
                          "position": %d
                        }
                        """.formatted(group.label(), position), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // Not ordered, the same reason CustomPagesWriter does not order a group's own landing page:
            // Docusaurus reads it as the category's own page, never as a sibling of the group's others.
            write(landing, 0, chapterDirectory, true);
            ChapterOrder order = ChapterOrder.of(group.pages(), List.of());
            for (CustomPage page : group.pages()) {
                write(pageOf(subject, page), order.positionOf(page), chapterDirectory, false);
            }
            return group.pages().size() + 1;
        }

        /**
         * @param isGroupLandingPage whether this is a group's own landing page, which carries
         *                           {@code doc_chapter_contents} the way {@code CustomPagesWriter} adds it -
         *                           an upload cannot set that key itself.
         */
        private void write(Page page, int position, Path directory, boolean isGroupLandingPage) {
            // The values as they are: what the published block looks like is the front matter's own
            // business, exactly as it is for the writer this stands in for.
            Map<String, Object> generated = UploadedFrontMatter.keys();
            generated.put("sidebar_position", position);
            if (isGroupLandingPage) {
                generated.put("doc_chapter_contents", true);
            }
            generated.put("doc_status", "custom");
            generated.put("doc_source", "upload");
            generated.put("doc_source_repository", REPOSITORY);
            generated.put("doc_source_ref", "main");
            generated.put("doc_source_revision", REVISION);
            generated.put("doc_uploaded_at", UPLOADED_AT.toString());
            generated.put("doc_uploaded_at_display", DisplayTime.of(UPLOADED_AT));
            String uploaded = """
                    ---
                    title: %s
                    ---

                    # %s

                    %s
                    """.formatted(page.title(), page.title(), page.body());
            try {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(page.fileName()),
                        UploadedFrontMatter.rewritten(uploaded, generated), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * One uploaded page: what it is called on disk, what it says it is, what a team wrote in it, and where its
     * front matter asked to be placed.
     */
    private record Page(String fileName, String title, String body, OrderHint hint) {

        Page(String fileName, String title) {
            this(fileName, title, "Written by the team that owns this documentation.");
        }

        Page(String fileName, String title, String body) {
            this(fileName, title, body, OrderHint.NONE);
        }
    }
}
