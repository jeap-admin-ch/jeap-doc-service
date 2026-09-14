package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomPages;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.custom.UploadedFrontMatter;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

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

/**
 * A system documented entirely by uploads, for the browser tests.
 * <p>
 * The pages are written the way the site generator writes them - the body as it was uploaded, the front
 * matter generated - so what a browser sees here is what a reader gets from a real upload. What is skipped is
 * the endpoint and the object storage, which {@code CustomDocsGenerationIT} covers end to end.
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

            ```plantuml
            @startuml
            Alice -> Bob: hello
            @enduml
            ```
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
                        new Page(RAW_HTML_PAGE + ".md", "Raw HTML as it was uploaded", RAW_HTML_BODY))));
        pages.put(new CustomSubject(Site.DEFAULT_SITE, SubjectKind.LIBRARY, system, library), Map.of(
                "12-glossary", List.of(new Page("terms.md", "The terms of the client"))));
        List<CustomSet> sets = new ArrayList<>(setsOf(pages));
        sets.add(micrositeOf(system));
        return SystemDocumentation.ofUploadsOnly(Site.DEFAULT_SITE, system,
                new CustomDocumentation(sets), new WritesThePages(pages));
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
                            i + 1, false));
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

    /** Writes the pages the way the site generator does: the body as uploaded, the front matter generated. */
    private record WritesThePages(Map<CustomSubject, Map<String, List<Page>>> pages) implements CustomPages {

        /**
         * The page that frames the microsite, written the way {@code CustomPagesWriter} writes it: what a
         * browser has to drive is the frame the site template builds out of this front matter.
         */
        @Override
        public int writeMicrositesInto(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
            if (subject.kind() != SubjectKind.SYSTEM || !MICROSITE_LOCATION.equals(chapterFolder)) {
                return 0;
            }
            Map<String, Object> generated = UploadedFrontMatter.keys();
            generated.put("title", MICROSITE_LABEL);
            generated.put("sidebar_position", 50);
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
            try {
                Files.createDirectories(chapterDirectory);
                Files.writeString(chapterDirectory.resolve(MICROSITE_TOPIC + "-microsite.md"),
                        UploadedFrontMatter.rewritten("Published by the team that owns it.\n", generated),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return 1;
        }

        @Override
        public int writeInto(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
            List<Page> inChapter = pages.getOrDefault(subject, Map.of())
                    .getOrDefault(chapterFolder, List.of());
            List<Page> sorted = inChapter.stream()
                    .sorted(java.util.Comparator.comparing(Page::title)).toList();
            for (int i = 0; i < sorted.size(); i++) {
                write(sorted.get(i), i + 1, chapterDirectory);
            }
            return sorted.size();
        }

        private void write(Page page, int position, Path directory) {
            // The values as they are: what the published block looks like is the front matter's own
            // business, exactly as it is for the writer this stands in for.
            Map<String, Object> generated = UploadedFrontMatter.keys();
            generated.put("sidebar_position", position);
            generated.put("doc_status", "custom");
            generated.put("doc_source", "upload");
            generated.put("doc_source_repository", REPOSITORY);
            generated.put("doc_source_ref", "main");
            generated.put("doc_source_revision", REVISION);
            generated.put("doc_uploaded_at", UPLOADED_AT.toString());
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

    /** One uploaded page: what it is called on disk, what it says it is, and what a team wrote in it. */
    private record Page(String fileName, String title, String body) {

        Page(String fileName, String title) {
            this(fileName, title, "Written by the team that owns this documentation.");
        }
    }
}
