package ch.admin.bit.jeap.doc.domain.upload.validation;

import ch.admin.bit.jeap.doc.domain.Slugs;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.NumberPrefixes;
import ch.admin.bit.jeap.doc.domain.template.ReservedNames;
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
     * The names a chapter's generated pages occupy, which the site generator reads from the same place - see
     * {@link ReservedNames}. <b>{@code _category_.json} is not among them</b> and does not need to be: it
     * begins with an underscore, which is refused for every template.
     */
    static final Set<String> LANDING_PAGE_NAMES = ReservedNames.LANDING_PAGE_NAMES;

    /** The longest a path may be. One absurd path among sane ones is a mistake in one path, not a bad tree. */
    public static final int MAX_PATH_LENGTH = 1024;

    private final StructureTemplates templates;
    private final UploadProperties properties;
    private final CustomProperties customProperties;

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
            return report(placement.template(), 0, 0, List.of(), List.of(), List.of(),
                    List.of(StructureFinding.ofTree(FindingCode.UNKNOWN_TEMPLATE,
                            "'%s' is not a structure template of this doc service. It offers %s."
                                    .formatted(placement.template(), String.join(", ", sorted(templates.ids()))))));
        }

        List<String> ignored = given.stream().filter(StructureValidation::isDropped).toList();
        List<String> checked = given.stream().filter(path -> !isDropped(path)).toList();
        boolean html = placement.sourceFormat() == SourceFormat.HTML;
        // Markdown is bounded by its template's allowlist and what the instance adds to it, a microsite by the
        // instance's denylist: a microsite follows no template, and a build emits file types nobody listed.
        Set<String> allowed = allowedExtensionsOf(template.get());
        List<String> allowedExtensions = html ? List.of() : sorted(allowed);
        List<String> refusedExtensions = html ? sorted(customProperties.getRefusedExtensions()) : List.of();
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
            findings.addAll(markdownFindings(placement, template.get(), allowed, checked));
        }
        return report(template.get().id(), checked.size(), ignored.size(), allowedFolders, allowedExtensions,
                refusedExtensions, findings);
    }

    /** The template's extensions and the asset types this instance adds. The only place an extension is decided. */
    private Set<String> allowedExtensionsOf(StructureTemplate template) {
        Set<String> allowed = new HashSet<>(template.allowedFileExtensions());
        allowed.addAll(customProperties.getAdditionalAssetExtensions());
        return allowed;
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
    // Guard clauses: one continue per rule, so the first finding about a path is the only one reported.
    @SuppressWarnings("java:S135")
    private List<StructureFinding> markdownFindings(DocumentationPlacement placement, StructureTemplate template,
                                                    Set<String> allowed, List<String> checked) {
        List<StructureFinding> findings = new ArrayList<>();
        Set<String> duplicated = duplicatesOf(checked);
        Set<String> colliding = collidingDocumentsOf(checked);
        Set<String> folders = foldersOf(checked);
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
                Optional<StructureFinding> folderFinding =
                        folderFinding(path, segments, template, chapter.get(), placement);
                if (folderFinding.isPresent()) {
                    findings.add(folderFinding.get());
                    continue;
                }
            }
            if (folders.contains(path)) {
                findings.add(StructureFinding.of(FindingCode.COLLIDING_NAME, path,
                        ("'%s' is a file, and other files of the set lie in a folder of that name. One path "
                         + "cannot be both, and the build of this part would fail writing it. Rename the file "
                         + "or the folder.").formatted(segments[segments.length - 1])));
                continue;
            }
            nameFinding(path, segments[segments.length - 1], template, allowed, chapter.get(), placement,
                    colliding).ifPresent(findings::add);
        }
        return findings;
    }

    /**
     * The rules about the folders between a chapter and a file: only an asset lies in one, not too deep, each
     * folder's name follows the rules of a file's name, and the folder right below the chapter is not one of
     * the names the doc service writes there itself.
     */
    private static Optional<StructureFinding> folderFinding(String path, String[] segments,
                                                            StructureTemplate template, StructureChapter chapter,
                                                            DocumentationPlacement placement) {
        String name = segments[segments.length - 1];
        int depth = segments.length - 2;
        if (DocumentationPaths.MARKDOWN_EXTENSION.equals(extensionOf(name))) {
            return Optional.of(StructureFinding.of(FindingCode.NESTED_FOLDER, path,
                    ("'%s' is a page in the folder '%s'. A page lies directly in its chapter; a folder inside "
                     + "a chapter holds assets only.").formatted(name, segments[1])));
        }
        if (depth > MarkdownAssetRules.MAX_FOLDER_DEPTH) {
            return Optional.of(StructureFinding.of(FindingCode.NESTED_FOLDER, path,
                    "'%s' is %d folders below its chapter, and at most %d are allowed."
                            .formatted(name, depth, MarkdownAssetRules.MAX_FOLDER_DEPTH)));
        }
        for (int i = 1; i < segments.length - 1; i++) {
            Optional<StructureFinding> finding = prefixFinding(path, segments[i], "folder");
            if (finding.isPresent()) {
                return finding;
            }
        }
        // Only the first folder: below it the tree is the set's own, and a generated group such as
        // 'components' is a folder of the chapter, so an asset folder of that name would write into it.
        if (ReservedNames.isTaken(template, chapter, placement.subject(), segments[1])) {
            return Optional.of(StructureFinding.of(FindingCode.RESERVED_NAME, path,
                    ("'%s' is a name the doc service writes into %s itself, and a folder of that name would put "
                     + "this file among what it generates. Rename the folder.")
                            .formatted(segments[1], chapter.folder())));
        }
        return Optional.empty();
    }

    /** A hidden or an underscore name, for a file and a folder alike. */
    private static Optional<StructureFinding> prefixFinding(String path, String name, String kind) {
        if (name.startsWith(".")) {
            return Optional.of(StructureFinding.of(FindingCode.HIDDEN_NAME, path,
                    ("'%s' is a hidden %s. The ones a tool writes are ignored; this one was not, so it is "
                     + "either a %s that does not belong in the documentation or one that needs a name.")
                            .formatted(name, kind, kind)));
        }
        if (name.startsWith("_")) {
            // The site generator drops _*.md and _*/ from a build, and keeps _category_.json for itself.
            return Optional.of(StructureFinding.of(FindingCode.UNPUBLISHABLE_NAME, path,
                    ("'%s' begins with an underscore, and the site generator keeps those names for itself: "
                     + "what carries one is left out of the build or changes the chapter. Either way the "
                     + "upload publishes something other than what was meant. Rename the %s.")
                            .formatted(name, kind)));
        }
        return Optional.empty();
    }

    /** The rules about a file's own name: hidden, unpublishable, its extension, and what is reserved. */
    private Optional<StructureFinding> nameFinding(String path, String name, StructureTemplate template,
                                                   Set<String> allowed, StructureChapter chapter,
                                                   DocumentationPlacement placement, Set<String> colliding) {
        Optional<StructureFinding> prefix = prefixFinding(path, name, "file");
        if (prefix.isPresent()) {
            return prefix;
        }
        String extension = extensionOf(name);
        if (extension == null || !allowed.contains(extension)) {
            return Optional.of(StructureFinding.of(FindingCode.FORBIDDEN_EXTENSION, path,
                    ("'%s' is not a file %s documents with. The extensions it takes are on this report.")
                            .formatted(extension == null ? name : "." + extension, template.id())));
        }
        // Only a document can collide with a document: an image of a generated page's name is an image.
        if (!DocumentationPaths.MARKDOWN_EXTENSION.equals(extension)) {
            return Optional.empty();
        }
        String fileName = name.substring(0, name.length() - extension.length() - 1);
        // What the generator identifies it as, and not what it is called: a leading number comes off the name
        // before it becomes a document id - see NumberPrefixes. The landing-page rule right below is the one
        // exception and is asked of the name as written, because that is the name the generator asks it of.
        String document = NumberPrefixes.stripped(fileName);
        if (ReservedNames.isLandingPageName(fileName, chapter)) {
            return Optional.of(StructureFinding.of(FindingCode.RESERVED_NAME, path,
                    ("'%s' is read as the landing page of %s, and the doc service generates that page. Two "
                     + "documents at one URL fail the build of this part, so rename the page.")
                            .formatted(fileName, chapter.folder())));
        }
        if (ReservedNames.isTheGeneratedIndex(document)) {
            // Not the landing-page rule above: '01-index.md' is not read as the landing page - that is decided
            // on the name as written - but it is identified as 'index' all the same, which is the document the
            // generated landing page of the chapter already is. Two documents of one id fail the build just as
            // two at one URL do.
            return Optional.of(StructureFinding.of(FindingCode.RESERVED_NAME, path,
                    ("'%s' is identified as '%s', because a leading number is not part of a document's name, "
                     + "and that is the landing page the doc service generates for %s. Rename the page.")
                            .formatted(name, document, chapter.folder())));
        }
        if (ReservedNames.isGeneratedByTheTemplate(template, chapter, placement.subject(), document)) {
            return Optional.of(StructureFinding.of(FindingCode.RESERVED_NAME, path,
                    ("'%s' is generated into %s by the doc service. Two documents at one URL fail the build of "
                     + "this part, so rename the page.").formatted(document, chapter.folder())));
        }
        if (colliding.contains(documentKeyOf(chapter.folder(), document))) {
            return Optional.of(StructureFinding.of(FindingCode.COLLIDING_NAME, path,
                    ("'%s' is published as '%s' in %s, and so is another document of that chapter. A leading "
                     + "number is not part of the page's URL, so the two would be one route and the build of "
                     + "this part would fail. Give them names that differ in more than a number.")
                            .formatted(name, document, chapter.folder())));
        }
        return Optional.empty();
    }

    /** An HTML upload is a microsite: no chapters, its own allowlist, and an entry point. */
    private List<StructureFinding> micrositeFindings(DocumentationPlacement placement, StructureTemplate template,
                                                     List<String> checked) {
        List<StructureFinding> findings = new ArrayList<>();
        Set<String> duplicated = duplicatesOf(checked);
        // Distinct, so a path that appears three times is one finding rather than three - and so that a
        // second guard against reporting it twice is not needed.
        for (String path : new LinkedHashSet<>(checked)) {
            micrositeFinding(path, duplicated).ifPresent(findings::add);
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

    /** The first rule one path of a microsite breaks, if it breaks any. */
    private Optional<StructureFinding> micrositeFinding(String path, Set<String> duplicated) {
        Optional<StructureFinding> pathFinding = pathHygiene(path, duplicated);
        if (pathFinding.isPresent()) {
            return pathFinding;
        }
        if (MicrositeRules.SEARCH_TEXT.equals(path)) {
            return Optional.of(StructureFinding.of(FindingCode.RESERVED_PATH, path,
                    ("'%s' is written by the doc service itself, beside the files of this set, and a "
                     + "set may not carry it. Rename the file.").formatted(MicrositeRules.SEARCH_TEXT)));
        }
        String extension = extensionOf(path.substring(path.lastIndexOf('/') + 1));
        if (!MicrositeRules.allows(extension, customProperties.getRefusedExtensions())) {
            return Optional.of(StructureFinding.of(FindingCode.FORBIDDEN_EXTENSION, path,
                    ("'.%s' is not a file a published microsite may carry. The extensions it refuses "
                     + "are on this report.").formatted(extension)));
        }
        return Optional.empty();
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

    /**
     * The documents of one chapter that two different file names would publish at one route, as
     * {@code <chapter>/<document>}.
     * <p>
     * Two file names, because an identical path is {@link FindingCode#DUPLICATE_PATH} and is reported as that:
     * the paths are made distinct first. What is left is {@code foo.md} beside {@code 1-foo.md}, which are two
     * files of the set and one page of the site.
     * <p>
     * Markdown only. An image is served under the name it has, so an image and a document of one name are two
     * different things at two different URLs - which is the same reason the reserved names are checked for
     * documents alone.
     */
    @SuppressWarnings("java:S135") // Guard clauses: one continue per kind of path this rule does not look at.
    private static Set<String> collidingDocumentsOf(List<String> paths) {
        Set<String> seen = new HashSet<>();
        Set<String> twice = new HashSet<>();
        for (String path : new LinkedHashSet<>(paths)) {
            String[] segments = path.split("/");
            if (segments.length != 2) {
                continue;
            }
            String name = segments[1];
            String extension = extensionOf(name);
            if (!DocumentationPaths.MARKDOWN_EXTENSION.equals(extension)) {
                continue;
            }
            String document = NumberPrefixes.stripped(name.substring(0, name.length() - extension.length() - 1));
            String key = documentKeyOf(segments[0], document);
            if (!seen.add(key)) {
                twice.add(key);
            }
        }
        return twice;
    }

    /** How a document of a chapter is named in {@link #collidingDocumentsOf}. */
    private static String documentKeyOf(String chapterFolder, String document) {
        return chapterFolder + "/" + document.toLowerCase(Locale.ROOT);
    }

    /**
     * Every folder inside a chapter that a path of the set lies in, as {@code <chapter>/<folder>/...}.
     * <p>
     * A file at one of these paths would be a file and a folder at once - {@code 1-intro/a.png} beside
     * {@code 1-intro/a.png/b.png} - which no file system writes.
     */
    private static Set<String> foldersOf(List<String> paths) {
        Set<String> folders = new HashSet<>();
        for (String path : paths) {
            String[] segments = path.split("/");
            StringBuilder folder = new StringBuilder(segments[0]);
            // From the first folder below the chapter: the chapter itself is a folder every file lies in.
            for (int i = 1; i < segments.length - 1; i++) {
                folder.append('/').append(segments[i]);
                folders.add(folder.toString());
            }
        }
        return folders;
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
                                   List<String> extensions, List<String> refused,
                                   List<StructureFinding> findings) {
        List<StructureFinding> ordered = findings.stream().sorted(StructureReport.ORDER).toList();
        int max = properties.getValidation().getMaxFindings();
        List<StructureFinding> kept = ordered.size() <= max ? ordered : ordered.subList(0, max);
        return new StructureReport(template, checked, ignored, folders, extensions, refused, kept,
                ordered.size() - kept.size());
    }
}
