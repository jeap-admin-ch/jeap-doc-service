package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPages;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One system as a build sees it: what the architecture model holds about it, and what has been uploaded for
 * it.
 * <p>
 * <b>The two models are joined here and nowhere else.</b> They are stored apart, nothing links them, and a
 * subject can be in either without being in the other - a system documented before anything is deployed is in
 * the uploaded one alone, and a system nobody has written about is in the imported one alone. A structure
 * template is handed this, so it never has to ask which case it is in: it asks what it needs.
 *
 * @param site   the documentation site being built
 * @param slug   the path segment this system is documented under
 * @param name   the name a page shows, which is the model's spelling where there is one and the slug otherwise
 * @param model  what the architecture model holds, or empty when it does not hold this system
 * @param custom what has been uploaded for this system, its components and its libraries
 * @param pages  writes the uploaded pages of a chapter into the tree
 */
public record SystemDocumentation(
        String site,
        String slug,
        String name,
        Optional<DocumentedSystem> model,
        CustomDocumentation custom,
        CustomPages pages) {

    /** A system the architecture model holds, with whatever was uploaded for it. */
    public static SystemDocumentation of(String site, DocumentedSystem system, CustomDocumentation custom,
                                         CustomPages pages) {
        return new SystemDocumentation(site, system.slug(), system.name(), Optional.of(system), custom, pages);
    }

    /**
     * A system only the uploaded documentation knows. Its name is its slug: nothing else knows how it is
     * spelled, and an upload only ever carries a slug.
     */
    public static SystemDocumentation ofUploadsOnly(String site, String slug, CustomDocumentation custom,
                                                    CustomPages pages) {
        return new SystemDocumentation(site, slug, slug, Optional.empty(), custom, pages);
    }

    /** Whether the architecture model holds this system at all. */
    public boolean isInTheArchitectureModel() {
        return model.isPresent();
    }

    /** The subject of the system itself, for asking what was uploaded for it. */
    public CustomSubject subject() {
        return new CustomSubject(site, SubjectKind.SYSTEM, slug, null);
    }

    /** The subject of one component of this system. */
    public CustomSubject componentSubject(String componentSlug) {
        return new CustomSubject(site, SubjectKind.COMPONENT, slug, componentSlug);
    }

    /** The chapters of this system's own documentation that carry uploaded pages. */
    public Set<String> customChaptersOfTheSystem() {
        return custom.chapterFoldersOf(subject());
    }

    /** The chapters of one component's documentation that carry uploaded pages. */
    public Set<String> customChaptersOfComponent(String componentSlug) {
        return custom.chapterFoldersOf(componentSubject(componentSlug));
    }

    /**
     * The components to document: those of the architecture model, and those only an upload knows.
     * <p>
     * A component can be documented before it is deployed, exactly as a system can. It has no model entry, so
     * what a page can say about it is what the upload carries.
     */
    public List<ComponentDocumentation> components() {
        List<ComponentDocumentation> components = new ArrayList<>();
        List<String> known = new ArrayList<>();
        for (DocumentedComponent component : model.map(DocumentedSystem::components).orElseGet(List::of)) {
            components.add(new ComponentDocumentation(component.slug(), component.name(),
                    Optional.of(component)));
            known.add(component.slug());
        }
        for (CustomSubject documented : custom.documentedComponents()) {
            if (!known.contains(documented.name())) {
                components.add(new ComponentDocumentation(documented.name(), documented.name(),
                        Optional.empty()));
            }
        }
        return List.copyOf(components);
    }

    /** The libraries of this system, which no architecture model ever holds. */
    public List<CustomSubject> libraries() {
        return custom.libraries();
    }

    /**
     * One component as a build sees it, the same way as a system: what the model holds, or nothing.
     *
     * @param slug  the path segment the component is documented under
     * @param name  the name a page shows
     * @param model what the architecture model holds, or empty
     */
    public record ComponentDocumentation(String slug, String name, Optional<DocumentedComponent> model) {

        public boolean isInTheArchitectureModel() {
            return model.isPresent();
        }
    }
}
