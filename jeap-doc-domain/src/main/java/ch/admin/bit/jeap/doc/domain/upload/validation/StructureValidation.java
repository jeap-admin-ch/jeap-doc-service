package ch.admin.bit.jeap.doc.domain.upload.validation;

import ch.admin.bit.jeap.doc.domain.Slugs;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Would this path tree be accepted?
 * <p>
 * <b>The only thing that walks a path.</b> A structure template answers what its chapters are, what
 * extensions it takes and what it generates; deciding, wording and ordering is here, so that the finding
 * codes are one set whatever methodology is named - see {@code docs/upload-validation.md}.
 * <p>
 * The endpoint that asks is advisory: a pipeline may skip it, and an upload is accepted without it. The rules
 * are in the domain so that the publication can apply the same ones once it writes an upload into a site.
 */
@Service
@RequiredArgsConstructor
public class StructureValidation {

    /**
     * The names the site generator reads as a chapter's landing page, beside the chapter folder's own name.
     * <p>
     * Every chapter has a generated landing page, so a document of one of these names is a second document at
     * that page's URL. <b>{@code _category_.json} is not on this list</b> and does not need to be: it begins
     * with an underscore, which is refused for every template - and naming it here would take a dependency
     * onto {@code jeap-doc-markdown}, where it is spelled.
     */
    static final Set<String> LANDING_PAGE_NAMES = Set.of(DocumentationPaths.INDEX_SEGMENT, "readme");

    /** The longest a path may be. One absurd path among sane ones is a mistake in one path, not a bad tree. */
    public static final int MAX_PATH_LENGTH = 1024;

    private final StructureTemplates templates;
    private final UploadProperties properties;

    /**
     * Checks a tree against where it would be placed.
     *
     * @param placement what the upload documents, and which template and source format it follows
     * @param paths     every file of the set, relative to its root, separated by {@code /}
     */
    public StructureReport validate(DocumentationPlacement placement, List<String> paths) {
        List<String> given = paths == null ? List.of() : paths;
        Optional<StructureTemplate> template = templates.find(placement.template());
        if (template.isEmpty()) {
            // Nothing else can be said: the chapters, the extensions and the generated names are all the
            // template's, and there is none.
            return report(placement.template(), 0, 0, List.of(), List.of(),
                    List.of(StructureFinding.ofTree(FindingCode.UNKNOWN_TEMPLATE,
                            "'%s' is not a structure template of this doc service. It offers %s."
                                    .formatted(placement.template(), String.join(", ", sorted(templates.ids()))))));
        }

        List<String> ignored = given.stream().filter(StructureValidation::isDropped).toList();
        List<String> checked = given.stream().filter(path -> !isDropped(path)).toList();
        boolean html = placement.sourceFormat() == SourceFormat.HTML;
        List<String> allowedExtensions =
                sorted(html ? MicrositeRules.allowedFileExtensions() : template.get().allowedFileExtensions());
        List<String> allowedFolders = template.get().orderedChapters().stream()
                .map(StructureChapter::folder)
                .toList();

        List<StructureFinding> findings = new ArrayList<>();
        if (checked.isEmpty()) {
            findings.add(StructureFinding.ofTree(FindingCode.EMPTY_TREE, ignored.isEmpty()
                    ? "The documentation set holds no file. Check the path the doc workflow points at."
                    : ("The documentation set holds nothing that would be published: all %d of its files are "
                       + "ones no documentation carries. Check the path the doc workflow points at.")
                            .formatted(ignored.size())));
        } else if (html) {
            findings.addAll(micrositeFindings(placement, template.get(), checked));
        } else {
            findings.addAll(markdownFindings(placement, template.get(), checked));
        }
        return report(template.get().id(), checked.size(), ignored.size(), allowedFolders, allowedExtensions,
                findings);
    }

    /**
     * What is dropped before any rule runs: the files nobody wrote, and a null, which is not a path at all.
     * <p>
     * A finding about a null would have to carry no path, and a report presents a finding with no path as one
     * about the whole tree.
     */
    private static boolean isDropped(String path) {
        return path == null || IgnoredPaths.isIgnored(path);
    }

    /** The eight path rules and the two set-level ones, in the order the finding codes are documented in. */
    private List<StructureFinding> markdownFindings(DocumentationPlacement placement, StructureTemplate template,
                                                    List<String> checked) {
        List<StructureFinding> findings = new ArrayList<>();
        Set<String> duplicated = duplicatesOf(checked);
        // Distinct, so a path that appears three times is one finding rather than three - and so that a
        // second guard against reporting it twice is not needed.
        for (String path : new LinkedHashSet<>(checked)) {
            Optional<StructureFinding> pathFinding = pathHygiene(path, duplicated);
            if (pathFinding.isPresent()) {
                findings.add(pathFinding.get());
                continue;
            }
            String[] segments = path.split("/");
            if (segments.length < 2) {
                findings.add(StructureFinding.of(FindingCode.FILE_OUTSIDE_CHAPTER, path,
                        "A file at the root of the documentation set belongs to no chapter, so nothing would "
                        + "publish it. Move it into the chapter it documents."));
                continue;
            }
            Optional<StructureChapter> chapter = template.chapterOfFolder(segments[0]);
            if (chapter.isEmpty()) {
                findings.add(StructureFinding.of(FindingCode.UNKNOWN_CHAPTER, path,
                        unknownChapterMessage(template, segments[0])));
                continue;
            }
            if (segments.length > 2) {
                findings.add(StructureFinding.of(FindingCode.NESTED_FOLDER, path,
                        ("'%s' is a folder inside a chapter. %s has no subfolders; the pages of a chapter lie "
                         + "directly in it, and an image lies beside the page that shows it.")
                                .formatted(segments[1], template.id())));
                continue;
            }
            nameFinding(path, segments[1], template, chapter.get(), placement).ifPresent(findings::add);
        }
        return findings;
    }

    /** The rules about a file's own name: hidden, unpublishable, its extension, and what is reserved. */
    private Optional<StructureFinding> nameFinding(String path, String name, StructureTemplate template,
                                                   StructureChapter chapter, DocumentationPlacement placement) {
        if (name.startsWith(".")) {
            return Optional.of(StructureFinding.of(FindingCode.HIDDEN_NAME, path,
                    ("'%s' is a hidden file. The ones a tool writes are ignored; this one was not, so it is "
                     + "either a file that does not belong in the documentation or a page that needs a name.")
                            .formatted(name)));
        }
        if (name.startsWith("_")) {
            // True of both readings: the site generator drops _*.md from a build, and the names it keeps for
            // itself - _category_.json - are its own. Either way the upload succeeds and does not publish
            // what the author meant.
            return Optional.of(StructureFinding.of(FindingCode.UNPUBLISHABLE_NAME, path,
                    ("'%s' begins with an underscore, and the site generator keeps those names for itself: a "
                     + "page of that name is left out of the build, and a navigation file of that name would "
                     + "change the chapter. Either way the upload publishes something other than the page. "
                     + "Rename it.").formatted(name)));
        }
        String extension = extensionOf(name);
        if (extension == null || !template.allowedFileExtensions().contains(extension)) {
            return Optional.of(StructureFinding.of(FindingCode.FORBIDDEN_EXTENSION, path,
                    ("'%s' is not a file %s documents with. The extensions it takes are on this report.")
                            .formatted(extension == null ? name : "." + extension, template.id())));
        }
        // Only a document can collide with a document: an image of a generated page's name is an image.
        if (!DocumentationPaths.MARKDOWN_EXTENSION.equals(extension)) {
            return Optional.empty();
        }
        String document = name.substring(0, name.length() - extension.length() - 1);
        if (isLandingPageName(document, chapter)) {
            return Optional.of(StructureFinding.of(FindingCode.RESERVED_NAME, path,
                    ("'%s' is read as the landing page of %s, and the doc service generates that page. Two "
                     + "documents at one URL fail the build of this part, so rename the page.")
                            .formatted(document, chapter.folder())));
        }
        if (template.generatedNames(chapter, placement.subject()).contains(document)) {
            return Optional.of(StructureFinding.of(FindingCode.RESERVED_NAME, path,
                    ("'%s' is generated into %s by the doc service. Two documents at one URL fail the build of "
                     + "this part, so rename the page.").formatted(document, chapter.folder())));
        }
        return Optional.empty();
    }

    /**
     * Whether the site generator would read this document as the chapter's landing page: {@code index},
     * {@code readme} or the chapter folder's own name.
     * <p>
     * Folded, because that is how the generator decides it - so {@code README.md} and {@code INDEX.MD} resolve
     * to the same URL as the generated landing page and would fail the build as a duplicate route.
     */
    private static boolean isLandingPageName(String document, StructureChapter chapter) {
        String folded = document.toLowerCase(Locale.ROOT);
        return LANDING_PAGE_NAMES.contains(folded)
               || folded.equals(chapter.folder().toLowerCase(Locale.ROOT));
    }

    /** An HTML upload is a microsite: no chapters, its own allowlist, and an entry point. */
    private List<StructureFinding> micrositeFindings(DocumentationPlacement placement, StructureTemplate template,
                                                     List<String> checked) {
        List<StructureFinding> findings = new ArrayList<>();
        Set<String> duplicated = duplicatesOf(checked);
        // Distinct, so a path that appears three times is one finding rather than three - and so that a
        // second guard against reporting it twice is not needed.
        for (String path : new LinkedHashSet<>(checked)) {
            Optional<StructureFinding> pathFinding = pathHygiene(path, duplicated);
            if (pathFinding.isPresent()) {
                findings.add(pathFinding.get());
                continue;
            }
            String extension = extensionOf(path.substring(path.lastIndexOf('/') + 1));
            if (!MicrositeRules.allows(extension)) {
                findings.add(StructureFinding.of(FindingCode.FORBIDDEN_EXTENSION, path,
                        ("'%s' is not a file a published microsite is made of. The extensions it may carry "
                         + "are on this report.").formatted(extension == null ? path : "." + extension)));
            }
        }
        if (checked.stream().noneMatch(MicrositeRules.ENTRY_POINT::equals)) {
            findings.add(StructureFinding.ofTree(FindingCode.MISSING_ENTRY_POINT,
                    ("A microsite is opened at its %s, and there is none at the root of this set.")
                            .formatted(MicrositeRules.ENTRY_POINT)));
        }
        if (template.chapterOfFolder(placement.location()).isEmpty()) {
            findings.add(StructureFinding.ofTree(FindingCode.UNKNOWN_LOCATION,
                    ("'%s' is not a chapter of %s, so there is nowhere to embed this microsite. The chapters "
                     + "are on this report.").formatted(placement.location(), template.id())));
        }
        return findings;
    }

    /**
     * Rules 1 and 2, which hold for every source format: the path is one that can be published at all, and it
     * appears once.
     */
    private Optional<StructureFinding> pathHygiene(String path, Set<String> duplicated) {
        if (!isNormalized(path)) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_PATH, path,
                    "A path has to be relative, separated by '/', and free of '.', '..', a backslash and a "
                    + "control character, and at most " + MAX_PATH_LENGTH + " characters long."));
        }
        if (duplicated.contains(path)) {
            return Optional.of(StructureFinding.of(FindingCode.DUPLICATE_PATH, path,
                    "The same path appears more than once in the documentation set."));
        }
        return Optional.empty();
    }

    private static boolean isNormalized(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        if (path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
            return false;
        }
        for (char character : path.toCharArray()) {
            if (Character.isISOControl(character)) {
                return false;
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rule 4's message tries to be useful rather than only correct: a folder that is recognisably a chapter
     * written the wrong way is named, and anything else is left to the list of chapters on the report.
     * <p>
     * <b>The name decides before the number does.</b> A folder carrying a chapter's name under the wrong
     * number - {@code 4-building-block-view} - means the chapter it names, and answering it with the chapter
     * its number happens to hit would send the reader to the wrong one. The number is what is left when the
     * name matches nothing, which is the case of a folder written in another language.
     */
    private static String unknownChapterMessage(StructureTemplate template, String folder) {
        Optional<StructureChapter> meant = chapterNamedBy(template, folder)
                .or(() -> chapterNumberedBy(template, folder));
        return meant
                .map(chapter -> "'%s' is not a chapter of %s. Did you mean '%s'?"
                        .formatted(folder, template.id(), chapter.folder()))
                .orElseGet(() -> "'%s' is not a chapter of %s. Its chapters are on this report."
                        .formatted(folder, template.id()));
    }

    /**
     * The chapter whose name the folder carries - its URL segment or the slug of its title - whatever number
     * stands in front of it.
     */
    private static Optional<StructureChapter> chapterNamedBy(StructureTemplate template, String folder) {
        String name = folder.replaceFirst("^\\d+-", "");
        return template.orderedChapters().stream()
                .filter(chapter -> chapter.urlSegment().equalsIgnoreCase(name)
                                   || Slugs.toSlug(chapter.title()).equalsIgnoreCase(name))
                .findFirst();
    }

    /** The chapter the folder's number names, for a folder whose name matches no chapter at all. */
    private static Optional<StructureChapter> chapterNumberedBy(StructureTemplate template, String folder) {
        return template.orderedChapters().stream()
                .filter(chapter -> chapter.isNumbered() && folder.startsWith(chapter.number() + "-"))
                .findFirst();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> duplicatesOf(List<String> paths) {
        Set<String> seen = new HashSet<>();
        Set<String> twice = new HashSet<>();
        for (String path : paths) {
            if (!seen.add(path)) {
                twice.add(path);
            }
        }
        return twice;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    /** Orders the findings, applies the cap, and says how many it left out. */
    private StructureReport report(String template, int checked, int ignored, List<String> folders,
                                   List<String> extensions, List<StructureFinding> findings) {
        List<StructureFinding> ordered = findings.stream().sorted(StructureReport.ORDER).toList();
        int max = properties.getValidation().getMaxFindings();
        List<StructureFinding> kept = ordered.size() <= max ? ordered : ordered.subList(0, max);
        return new StructureReport(template, checked, ignored, folders, extensions, kept,
                ordered.size() - kept.size());
    }
}
