package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomPages;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
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
import java.util.List;
import java.util.Map;

/**
 * What a build hands a template, for the tests of what a template writes.
 * <p>
 * The pages are written by a stand-in rather than by the site generator's own writer: what this module is
 * responsible for is the tree and the chapters, and what an uploaded page looks like inside is tested where
 * it is written.
 */
final class Documented {

    static final String SITE = "default";

    private Documented() {
    }

    /** A system the architecture model holds, with nothing uploaded for it. */
    static SystemDocumentation of(DocumentedSystem system) {
        return documented(system, CustomDocumentation.nothing());
    }

    /** A system the architecture model holds, with pages uploaded for the given chapters. */
    static SystemDocumentation withUploads(DocumentedSystem system, Map<String, List<String>> pagesByChapter) {
        return documented(system, documentationOf(
                new CustomSubject(SITE, SubjectKind.SYSTEM, system.slug(), null), pagesByChapter));
    }

    /** A system only the uploaded documentation knows. */
    static SystemDocumentation uploadsOnly(String slug, Map<String, List<String>> pagesByChapter) {
        CustomDocumentation uploaded = documentationOf(
                new CustomSubject(SITE, SubjectKind.SYSTEM, slug, null), pagesByChapter);
        return SystemDocumentation.ofUploadsOnly(SITE, slug, uploaded, new WritesThePages(uploaded));
    }

    /** A system with one documented library. */
    static SystemDocumentation withALibrary(DocumentedSystem system, String library,
                                            Map<String, List<String>> pagesByChapter) {
        return documented(system, documentationOf(
                new CustomSubject(SITE, SubjectKind.LIBRARY, system.slug(), library), pagesByChapter));
    }

    /** A system whose component is documented and is in no architecture model. */
    static SystemDocumentation withADocumentedComponent(DocumentedSystem system, String component,
                                                        Map<String, List<String>> pagesByChapter) {
        return documented(system, documentationOf(
                new CustomSubject(SITE, SubjectKind.COMPONENT, system.slug(), component), pagesByChapter));
    }

    /** The writer is handed the same documentation the template is, exactly as a build hands it over. */
    private static SystemDocumentation documented(DocumentedSystem system, CustomDocumentation uploaded) {
        return SystemDocumentation.of(SITE, system, uploaded, new WritesThePages(uploaded));
    }

    private static CustomDocumentation documentationOf(CustomSubject subject,
                                                       Map<String, List<String>> pagesByChapter) {
        List<CustomPage> pages = new ArrayList<>();
        pagesByChapter.forEach((chapter, names) -> {
            for (int i = 0; i < names.size(); i++) {
                pages.add(new CustomPage(chapter, names.get(i), names.get(i), i + 1, false));
            }
        });
        CustomSetKey key = new CustomSetKey(subject.site(), subject.kind(), subject.system(), subject.name(),
                SourceFormat.MARKDOWN, Arc42Template.ID, null, null);
        return new CustomDocumentation(List.of(new CustomSet(1L, key, null, 1, "current/x", "abc", 1,
                new CustomProvenance("docs", "main", "cafe", Instant.EPOCH, null, Instant.EPOCH), pages)));
    }

    /**
     * Writes a page per file the set names, which is what a test about the tree has to see.
     * <p>
     * <b>It really writes them.</b> A double that answered zero and wrote nothing let every tree test assert
     * the frame the template generates and never the uploaded pages inside it - so a chapter folder created
     * for a set that then contributed no page was invisible to all of them.
     */
    private record WritesThePages(CustomDocumentation documentation) implements CustomPages {

        @Override
        public int writeInto(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
            List<CustomPage> pages = documentation.setOf(subject)
                    .map(set -> set.pagesOf(chapterFolder))
                    .orElseGet(List::of);
            try {
                Files.createDirectories(chapterDirectory);
                for (CustomPage page : pages) {
                    Files.writeString(chapterDirectory.resolve(page.fileName()),
                            "---\ntitle: \"%s\"\nsidebar_position: %d\ndoc_status: custom\n---\n"
                                    .formatted(page.title(), page.position()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return pages.size();
        }
    }
}
