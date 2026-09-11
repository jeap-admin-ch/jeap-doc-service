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

    private static final Instant UPLOADED_AT = Instant.parse("2026-09-01T07:15:00Z");

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
                "2-constraints", List.of(new Page("given.md", "What was given"))));
        pages.put(new CustomSubject(Site.DEFAULT_SITE, SubjectKind.LIBRARY, system, library), Map.of(
                "12-glossary", List.of(new Page("terms.md", "The terms of the client"))));
        return SystemDocumentation.ofUploadsOnly(Site.DEFAULT_SITE, system,
                new CustomDocumentation(setsOf(pages)), new WritesThePages(pages));
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
                    key.name(), SourceFormat.MARKDOWN, "arc42", null, null), revision,
                    "current/docs/browser/" + revision + "/bundle.zip", "abc", 100,
                    new CustomProvenance(REPOSITORY, "main", REVISION, UPLOADED_AT,
                            key.kind() == SubjectKind.LIBRARY ? LIBRARY_VERSION : null, UPLOADED_AT),
                    files));
            revision++;
        }
        return sets;
    }

    /** Writes the pages the way the site generator does: the body as uploaded, the front matter generated. */
    private record WritesThePages(Map<CustomSubject, Map<String, List<Page>>> pages) implements CustomPages {

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

                    Written by the team that owns this documentation.
                    """.formatted(page.title(), page.title());
            try {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(page.fileName()),
                        UploadedFrontMatter.rewritten(uploaded, generated), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** One uploaded page: what it is called on disk, and what it says it is. */
    private record Page(String fileName, String title) {
    }
}
