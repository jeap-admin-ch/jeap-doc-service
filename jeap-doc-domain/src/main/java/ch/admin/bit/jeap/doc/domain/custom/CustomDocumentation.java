package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

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
     * The set of one subject, if there is one.
     * <p>
     * At most one, because a build asks a documentation {@link #publishedBy one template} has narrowed: the
     * template and the source format are part of a set's key, and the two remaining parts of it - the section
     * and the slug of a microsite - belong to HTML alone.
     */
    public Optional<CustomSet> setOf(CustomSubject subject) {
        return sets.stream().filter(set -> set.subject().equals(subject)).findFirst();
    }

    /**
     * The chapter folders that carry pages of this subject, sorted.
     * <p>
     * Folder names rather than chapters: this model knows nothing about templates, and the template that asks
     * resolves a folder to one of its own chapters.
     */
    public Set<String> chapterFoldersOf(CustomSubject subject) {
        Set<String> folders = new TreeSet<>();
        for (CustomSet set : sets) {
            if (set.subject().equals(subject)) {
                set.pages().stream().filter(page -> !page.asset())
                        .forEach(page -> folders.add(page.chapter()));
            }
        }
        return folders;
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
