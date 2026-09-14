package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything that has been uploaded for one system: its own documentation, and that of its components and
 * libraries.
 * <p>
 * Read once per build and handed down to the structure templates, which is why it is a value and not a bean:
 * it belongs to one build of one part. What a template asks it is which chapters carry pages; the pages
 * themselves are written by {@link CustomPages}.
 */
public record CustomDocumentation(List<CustomSet> sets) {

    private static final CustomDocumentation NOTHING = new CustomDocumentation(List.of());

    public CustomDocumentation {
        sets = sets == null ? List.of() : List.copyOf(sets);
    }

    /** For a system nobody has uploaded anything for, and for an environment that documents none. */
    public static CustomDocumentation nothing() {
        return NOTHING;
    }

    public boolean isEmpty() {
        return sets.isEmpty();
    }

    /**
     * The Markdown sets one structure template publishes, and nothing else.
     * <p>
     * <b>A build asks this before it asks anything else.</b> A subject legitimately carries several sets - a
     * second methodology, or an HTML microsite beside its Markdown - and every question below is about the
     * pages of one tree: which chapters carry pages, which components have any, where a library's version
     * comes from. Answered over all the sets of a subject they would be answered from whichever set the
     * database happened to hand back first.
     */
    public CustomDocumentation publishedBy(String template) {
        return new CustomDocumentation(sets.stream()
                .filter(set -> set.key().template().equals(template))
                .filter(set -> set.key().sourceFormat() == SourceFormat.MARKDOWN)
                .toList());
    }

    /**
     * Everything one structure template publishes: the Markdown sets and the microsites alike.
     * <p>
     * <b>This is what a build narrows with, and {@link #publishedBy} is what answers a question about pages.</b>
     * A microsite has no page rows - it is served file by file - so a documentation narrowed to Markdown says
     * a chapter holding only a microsite does not exist, and the page that frames it is then written nowhere:
     * the set is stored, the files are served, and nothing on the site links to them.
     */
    public CustomDocumentation ofTemplate(String template) {
        return new CustomDocumentation(sets.stream()
                .filter(set -> set.key().template().equals(template))
                .toList());
    }

    /**
     * The set of one subject, if there is one.
     * <p>
     * At most one, because a build asks a documentation {@link #publishedBy one template} has narrowed: the
     * template and the source format are part of a set's key, and the two remaining parts of it - the section
     * and the slug of a microsite - belong to HTML alone.
     */
    public Optional<CustomSet> setOf(CustomSubject subject) {
        return sets.stream()
                .filter(set -> set.subject().equals(subject))
                // The Markdown set, whatever this documentation also holds: everything that asks for "the
                // set" of a subject is asking about its pages, and a microsite has none.
                .filter(set -> set.key().sourceFormat() == SourceFormat.MARKDOWN)
                .findFirst();
    }

    /**
     * Where the documentation of one subject came from: its Markdown set's upload, or - for a subject documented
     * only by microsites - the most recent of those.
     * <p>
     * <b>Not {@link #setOf}.</b> That one answers the Markdown set, which is right for every question about
     * pages, and a library documented only by a microsite has none - so its overview said nothing about the
     * version and the repository its upload had stated.
     */
    public Optional<CustomProvenance> provenanceOf(CustomSubject subject) {
        return setOf(subject).or(() -> sets.stream()
                        .filter(set -> set.subject().equals(subject))
                        .max(Comparator.comparingLong(CustomSet::revision)))
                .map(CustomSet::provenance);
    }

    /**
     * The chapter folders that carry documentation of this subject, sorted.
     * <p>
     * Folder names rather than chapters: this model knows nothing about templates, and the template that asks
     * resolves a folder to one of its own chapters.
     * <p>
     * <b>A microsite carries a chapter too.</b> It has no page rows - it is served file by file rather than
     * written into the tree - so counting only pages would leave a chapter whose sole content is a microsite
     * out of this set. The chapter would never be created, and the microsite would be stored, served and
     * absent from the navigation.
     */
    public Set<String> chapterFoldersOf(CustomSubject subject) {
        Set<String> folders = new TreeSet<>();
        for (CustomSet set : sets) {
            if (!set.subject().equals(subject)) {
                continue;
            }
            if (set.key().sourceFormat() == SourceFormat.HTML) {
                folders.add(set.key().location());
            }
            set.pages().stream().filter(page -> !page.asset())
                    .forEach(page -> folders.add(page.chapter()));
        }
        return folders;
    }

    /**
     * The microsites one template publishes for this subject in one chapter, ordered by their labels.
     * <p>
     * <b>Its own accessor, and not {@link #publishedBy}.</b> That one narrows to the markdown sets every
     * other question here is about - which chapters carry pages, where a version comes from - and widening
     * it would change every one of those answers.
     *
     * @param subject       whose documentation to look at
     * @param template      the methodology being generated, which is part of a set's identity
     * @param chapterFolder the chapter the microsite is embedded in
     */
    public List<Microsite> micrositesOf(CustomSubject subject, String template, String chapterFolder) {
        return sets.stream()
                .filter(set -> set.subject().equals(subject))
                .filter(set -> set.key().sourceFormat() == SourceFormat.HTML)
                .filter(set -> set.key().template().equals(template))
                .filter(set -> chapterFolder.equals(set.key().location()))
                .map(Microsite::of)
                .sorted(Comparator.comparing(Microsite::sortKey, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Microsite::topic))
                .toList();
    }

    /** The components of this system that have documentation, whether or not the architecture model has them. */
    public List<CustomSubject> documentedComponents() {
        return subjectsOfKind(SubjectKind.COMPONENT);
    }

    /** The libraries of this system, which no architecture model ever has. */
    public List<CustomSubject> libraries() {
        return subjectsOfKind(SubjectKind.LIBRARY);
    }

    /** Whether the system itself has documentation of its own, rather than only its components. */
    public boolean documentsTheSystem() {
        return sets.stream().anyMatch(set -> set.key().kind() == SubjectKind.SYSTEM);
    }

    private List<CustomSubject> subjectsOfKind(SubjectKind kind) {
        // A map keyed by the subject, so a subject with two sets - two templates, or Markdown beside a
        // microsite - is one entry, and the order is the one the sets came in.
        Map<CustomSubject, CustomSubject> found = new LinkedHashMap<>();
        for (CustomSet set : sets) {
            if (set.key().kind() == kind) {
                found.putIfAbsent(set.subject(), set.subject());
            }
        }
        return List.copyOf(found.keySet());
    }
}
