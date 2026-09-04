package ch.admin.bit.jeap.doc.domain.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The structure templates this instance has, resolved from what is on the classpath.
 * <p>
 * The site generator asks it for a system's pages, and the upload validation will check a {@code template}
 * parameter against it. A template is added to an instance by adding a module.
 */
@Slf4j
@Component
public class StructureTemplates {

    private final Map<String, StructureTemplate> byId = new LinkedHashMap<>();

    /**
     * Asked for rather than injected: an instance with no template module is a legitimate one, and injecting
     * the list directly would fail the startup with a missing-bean message instead of the warning below.
     */
    @Autowired
    public StructureTemplates(ObjectProvider<StructureTemplate> templates) {
        this(templates.orderedStream().toList());
    }

    public StructureTemplates(List<StructureTemplate> templates) {
        for (StructureTemplate template : templates) {
            StructureTemplate existing = byId.put(template.id(), template);
            if (existing != null) {
                throw new IllegalStateException(
                        ("Two structure templates are registered under the id '%s': %s and %s. An upload names "
                         + "a template by its id, so the ids have to be unique.")
                                .formatted(template.id(), existing.getClass().getName(),
                                        template.getClass().getName()));
            }
            requireCoherentChapters(template);
        }
        if (byId.isEmpty()) {
            log.warn("No structure template is on the classpath, so no documentation is generated from the "
                     + "architecture model. Add jeap-doc-template-arc42 to the instance.");
        } else {
            log.info("Structure templates: {}.", String.join(", ", byId.keySet()));
        }
    }

    /**
     * Checks the chapters of one template: all numbered or none, and no two of them in the same place.
     * <p>
     * Here rather than in {@link StructureChapter}, because none of it is a property of a chapter on its own -
     * it is a property of the set. And at startup rather than at the first build, like every other
     * configuration error of this service: a template is a module on the classpath, so a mistake in one is a
     * deployment's mistake and belongs in its log.
     */
    private static void requireCoherentChapters(StructureTemplate template) {
        List<StructureChapter> chapters = template.chapters();
        List<String> numbered = chapters.stream().filter(StructureChapter::isNumbered)
                .map(StructureChapter::folder).toList();
        List<String> unnumbered = chapters.stream().filter(chapter -> !chapter.isNumbered())
                .map(StructureChapter::folder).toList();
        if (!numbered.isEmpty() && !unnumbered.isEmpty()) {
            // Half a numbering is not an order. Either the numbers order the chapters or the alphabet does, and
            // a template that means one of them has said so on every chapter.
            throw new IllegalStateException(("The structure template '%s' (%s) numbers some of its chapters and "
                                             + "not others: numbered %s, unnumbered %s. A template numbers "
                                             + "every chapter or none - the numbers order the chapters, or the "
                                             + "titles do.")
                    .formatted(template.id(), template.getClass().getName(), numbered, unnumbered));
        }
        requireDistinct(template, chapters, StructureChapter::folder, "folder",
                "two chapters in one folder are one folder");
        // A numbered '5-glossary' and an unnumbered 'glossary' are one URL, and the second page would be
        // written over the first while the navigation still named both. It cannot happen within one kind, and
        // it is checked all the same: the cost is a set, and the alternative is a page that silently vanishes.
        requireDistinct(template, chapters, StructureChapter::urlSegment, "URL segment",
                "two chapters at one URL are one page");
        // And the numbers, because they are what the navigation is ordered by: two chapters numbered 5 pass
        // every check above - different folders, different URLs - and then carry the same position into their
        // category files, which leaves their order to whichever version of the site generator is installed.
        // That is the one thing orderedChapters() exists to prevent.
        requireDistinct(template, chapters.stream().filter(StructureChapter::isNumbered).toList(),
                chapter -> String.valueOf(chapter.number()), "number",
                "two chapters with one number have one place in the navigation");
    }

    private static void requireDistinct(StructureTemplate template, List<StructureChapter> chapters,
                                        Function<StructureChapter, String> of, String what, String why) {
        Set<String> seen = new LinkedHashSet<>();
        for (StructureChapter chapter : chapters) {
            if (!seen.add(of.apply(chapter))) {
                throw new IllegalStateException(("The structure template '%s' (%s) has two chapters with the %s "
                                                 + "'%s' - %s.")
                        .formatted(template.id(), template.getClass().getName(), what, of.apply(chapter), why));
            }
        }
    }

    /** The template with the given id, or empty when this instance has none. */
    public Optional<StructureTemplate> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every template, in the order they were registered. */
    public List<StructureTemplate> all() {
        return List.copyOf(byId.values());
    }

    /** The ids that exist, for a message that has to say what to write instead. */
    public Set<String> ids() {
        return Set.copyOf(byId.keySet());
    }
}
