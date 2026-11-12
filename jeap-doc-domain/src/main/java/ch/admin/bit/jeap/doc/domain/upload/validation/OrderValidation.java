package ch.admin.bit.jeap.doc.domain.upload.validation;

import ch.admin.bit.jeap.doc.domain.custom.ChapterOrder;
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.custom.Microsite;
import ch.admin.bit.jeap.doc.domain.custom.OrderHint;
import ch.admin.bit.jeap.doc.domain.custom.UploadedPlacement;
import ch.admin.bit.jeap.doc.domain.custom.UploadedTitles;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Would a page's own placement be accepted?
 * <p>
 * <b>The shape of what one page says, and whether it means anything.</b> This checks {@code sidebar_position},
 * {@code sidebar_after} and {@code sidebar_before} the way {@link StructureValidation} checks a path: is the
 * value one this service can read at all ({@link FindingCode#INVALID_POSITION},
 * {@link FindingCode#INVALID_REFERENCE}), and - for a page of a system, a component or a library - does a
 * reference name something that exists and does a set of references stay free of a cycle
 * ({@link FindingCode#UNKNOWN_REFERENCE}, {@link FindingCode#CYCLIC_ORDER}). The second half reuses
 * {@link ChapterOrder#namesInACycle}, the one definition of a cycle this service has.
 * <p>
 * <b>Only a markdown page has a placement of its own.</b> An HTML microsite carries none in its files - its
 * three upload parameters are not yet checked here - and a chapter's generated siblings carry none either:
 * they are declared by the template, not uploaded.
 * <p>
 * <b>A static site's pages cannot reference anything</b> - {@link #referenceFindingOf} already refuses that -
 * so the cross-reference checks below never run for one.
 */
@Service
@RequiredArgsConstructor
public class OrderValidation {

    private final StructureTemplates templates;
    private final CustomDocumentationRepository documentation;

    /**
     * Checks every markdown page of the tree for a placement this service cannot make sense of, and - for a
     * system's, a component's or a library's documentation - for a reference that names nothing or that would
     * introduce a cycle.
     *
     * @param accepted  the report {@link StructureValidation} already gave this tree - its own findings are
     *                  empty, because this runs only once that is true, and its template and counts travel
     *                  into the report this returns
     * @param placement what the upload documents, and in which format
     * @param site      the site the set is uploaded to, or null for the default site
     * @param paths     the tree, as received
     * @param bundle    where a page's front matter is read from - the same budget {@code HEAD_BYTES} bounds,
     *                  and read only because the tree already passed {@link StructureValidation}
     * @return a report naming what is wrong with one or more pages' own placement, or nothing where every
     * page's is fine
     */
    public Optional<StructureReport> check(StructureReport accepted, DocumentationPlacement placement, String site,
                                           List<String> paths, UploadedBundles.ReceivedBundle bundle) {
        if (placement.sourceFormat() == SourceFormat.HTML) {
            return Optional.empty();
        }
        boolean staticSite = placement.type() == DocumentationType.SITE_DOCS;
        List<String> markdownPaths = paths.stream()
                .filter(path -> !IgnoredPaths.isIgnored(path) && isMarkdownPage(path))
                .toList();

        List<StructureFinding> findings = new ArrayList<>();
        Map<String, OrderHint> hintOfPath = new LinkedHashMap<>();
        for (String path : markdownPaths) {
            byte[] head = bundle.head(path, UploadedTitles.HEAD_BYTES);
            UploadedPlacement raw = UploadedTitles.rawPlacementOf(head);
            positionFindingOf(raw, path).ifPresent(findings::add);
            referenceFindingOf(raw, path, staticSite).ifPresent(findings::add);
            // The tolerant reading, not the raw one: a reference already refused above - both directions at
            // once, or a value that is not a single name - reads here as no reference, exactly as it would
            // once this set is taken over. The cross-reference checks below have nothing further to say about
            // a page already refused for the shape of its own reference.
            hintOfPath.put(path, UploadedTitles.hintOf(head));
        }

        if (!staticSite) {
            findings.addAll(crossReferenceFindings(placement, site, markdownPaths, hintOfPath));
        }

        if (findings.isEmpty()) {
            return Optional.empty();
        }
        List<StructureFinding> ordered = findings.stream().sorted(StructureReport.ORDER).toList();
        return Optional.of(new StructureReport(accepted.template(), accepted.pathsChecked(),
                accepted.pathsIgnored(), accepted.allowedFolders(), accepted.allowedExtensions(),
                accepted.refusedExtensions(), ordered, 0));
    }

    private static Optional<StructureFinding> positionFindingOf(UploadedPlacement raw, String path) {
        if (!raw.positionIsInvalid()) {
            return Optional.empty();
        }
        return Optional.of(StructureFinding.of(FindingCode.INVALID_POSITION, path,
                ("'%s' is not a finite number, and sidebar_position has to be one - an integer or a decimal, "
                 + "not quoted.").formatted(raw.position())));
    }

    /** The first reference rule one page breaks, if it breaks any. */
    private static Optional<StructureFinding> referenceFindingOf(UploadedPlacement raw, String path,
                                                                  boolean staticSite) {
        if (staticSite && raw.namesAReference()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    "sidebar_after and sidebar_before place a page next to a named sibling of its chapter, "
                    + "and a static site has none: its pages are ordered by Docusaurus itself. Use "
                    + "sidebar_position instead."));
        }
        if (raw.namesBothDirections()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    "sidebar_after and sidebar_before cannot both be given: a page places itself next to at "
                    + "most one other item."));
        }
        if (raw.afterIsInvalid()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    ("'%s' is not a single, non-blank name, and sidebar_after has to be one.")
                            .formatted(raw.after())));
        }
        if (raw.beforeIsInvalid()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    ("'%s' is not a single, non-blank name, and sidebar_before has to be one.")
                            .formatted(raw.before())));
        }
        return Optional.empty();
    }

    /**
     * Groups the tree's markdown pages by chapter and checks each chapter's references against what the
     * chapter would actually hold: this upload's own pages, the template's generated siblings, and what is
     * currently stored for it.
     * <p>
     * Nothing is checked for a chapter this tree has no page in - there is nothing new to place there.
     */
    private List<StructureFinding> crossReferenceFindings(DocumentationPlacement placement, String site,
                                                           List<String> markdownPaths,
                                                           Map<String, OrderHint> hintOfPath) {
        Map<String, List<String>> pathsByChapter = new LinkedHashMap<>();
        for (String path : markdownPaths) {
            pathsByChapter.computeIfAbsent(chapterFolderOf(path), key -> new ArrayList<>()).add(path);
        }
        if (pathsByChapter.isEmpty()) {
            return List.of();
        }
        Optional<StructureTemplate> template = templates.find(placement.template());
        if (template.isEmpty()) {
            // Unknown template: StructureValidation already refused the tree over it, and this never runs on
            // a tree with findings of its own.
            return List.of();
        }
        CustomSubject subject = new CustomSubject(site, placement.subject(), placement.system(),
                placement.subjectName());
        CustomDocumentation stored = documentation.of(site, placement.system());
        CustomDocumentation storedMarkdown = stored.publishedBy(template.get().id());

        List<StructureFinding> findings = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : pathsByChapter.entrySet()) {
            String chapterFolder = entry.getKey();
            Optional<StructureChapter> chapter = template.get().chapterOfFolder(chapterFolder);
            if (chapter.isEmpty()) {
                // Unknown chapter: StructureValidation already refused the tree over it.
                continue;
            }
            findings.addAll(chapterFindings(template.get(), chapter.get(), subject, storedMarkdown, stored,
                    chapterFolder, entry.getValue(), hintOfPath));
        }
        return findings;
    }

    /** The findings of one chapter: every reference its pages name, checked against what that chapter holds. */
    private List<StructureFinding> chapterFindings(StructureTemplate template, StructureChapter chapter,
                                                    CustomSubject subject, CustomDocumentation storedMarkdown,
                                                    CustomDocumentation stored, String chapterFolder,
                                                    List<String> paths, Map<String, OrderHint> hintOfPath) {
        Set<String> known = new HashSet<>(template.generatedSiblings(chapter, subject.kind()));
        Map<String, String> referenceOf = new HashMap<>();

        for (String path : paths) {
            String name = ChapterOrder.nameOf(fileNameOf(path));
            known.add(name);
            addReference(referenceOf, name, hintOfPath.get(path));
        }
        storedMarkdown.setOf(subject).map(set -> set.pagesOf(chapterFolder)).orElse(List.of())
                .forEach(page -> {
                    String name = ChapterOrder.nameOf(page.fileName());
                    known.add(name);
                    addReference(referenceOf, name, page.hint());
                });
        for (Microsite microsite : stored.micrositesOf(subject, template.id(), chapterFolder)) {
            String name = Microsite.SEGMENT + "/" + microsite.topic();
            known.add(name);
            addReference(referenceOf, name, microsite.hint());
        }

        List<StructureFinding> findings = new ArrayList<>();
        for (String path : paths) {
            OrderHint hint = hintOfPath.get(path);
            String reference = hint == null ? null : hint.reference();
            if (reference != null && !known.contains(reference)) {
                findings.add(StructureFinding.of(FindingCode.UNKNOWN_REFERENCE, path,
                        ("'%s' is not a page of this chapter, a generated sibling of it, or a page or "
                         + "microsite currently stored for it.").formatted(reference)));
            }
        }

        Set<String> cyclic = ChapterOrder.namesInACycle(referenceOf);
        if (!cyclic.isEmpty()) {
            for (String path : paths) {
                if (cyclic.contains(ChapterOrder.nameOf(fileNameOf(path)))) {
                    findings.add(StructureFinding.of(FindingCode.CYCLIC_ORDER, path,
                            "This page's reference, followed from page to page, loops back to this one."));
                }
            }
        }
        return findings;
    }

    /** One name's outgoing reference, if it has one - a name with none is never a key, which is terminal. */
    private static void addReference(Map<String, String> referenceOf, String name, OrderHint hint) {
        if (hint != null && hint.reference() != null) {
            referenceOf.put(name, hint.reference());
        }
    }

    private static String chapterFolderOf(String path) {
        return path.split("/", 2)[0];
    }

    private static String fileNameOf(String path) {
        String[] segments = path.split("/");
        return segments[segments.length - 1];
    }

    private static boolean isMarkdownPage(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith("." + DocumentationPaths.MARKDOWN_EXTENSION);
    }
}
