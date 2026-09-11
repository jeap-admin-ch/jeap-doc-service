package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Cuts a site into one part per system, each carrying that system in <b>every</b> environment of the site.
 * <p>
 * A system is what everything else is already cut on - a team, a repository, an upload, a role - so an upload
 * maps to exactly one part, and a part is one team's documentation. Keeping a system's environments together
 * in one build is what lets the broken-link check still see them together: a page that exists on dev and not
 * on prod is the shape a generator bug takes.
 * <p>
 * What is left over is the shell part: the root page of each environment, the systems index and the page about
 * the documentation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSitePartition implements SitePartition {

    /** What the identifier of a part naming a system starts with, so that it cannot be the shell's. */
    static final String SYSTEM_PREFIX = "system-";

    private final ArchitectureModelSource architectureModel;

    /**
     * What has been documented on this site, which is the other half of what parts there are.
     * <p>
     * A system can be documented before anything is deployed, so the architecture model does not know every
     * system a site publishes. Without this, such a system has no part: nothing would ask for it to be
     * built, an operator could not see it, and the sweep of departed parts would take what one upload
     * managed to publish.
     */
    private final CustomDocumentationRepository documentation;

    @Override
    public String axis() {
        return "system";
    }

    /**
     * The shell, then one part per system, in alphabetical order.
     * <p>
     * <b>The union of the two models</b>: every system the architecture model of any environment knows, and
     * every system something has been uploaded for. The union across environments and not one part per
     * environment and system - a system deployed on dev only is one part all the same, with one of its trees
     * empty - and the union across the models for the same reason: a system that is documented and not
     * deployed is still one part.
     */
    @Override
    public List<SitePart> partsOf(Site site) {
        List<SitePart> parts = new ArrayList<>();
        parts.add(shellOf(site));
        for (String slug : systemSlugsOf(site)) {
            parts.add(systemPart(site, slug));
        }
        return List.copyOf(parts);
    }

    @Override
    public Optional<SitePart> partOf(Site site, String partId) {
        if (SitePart.SHELL.equals(partId)) {
            return Optional.of(shellOf(site));
        }
        if (partId == null || !partId.startsWith(SYSTEM_PREFIX) || partId.length() == SYSTEM_PREFIX.length()) {
            return Optional.empty();
        }
        return Optional.of(systemPart(site, partId.substring(SYSTEM_PREFIX.length())));
    }

    /**
     * The shell carries every environment's tree - it writes the root page, the systems index and the page
     * about the documentation into each of them - and owns no subtree of its own: it answers for whatever no
     * system's part claims.
     */
    @Override
    public SitePart shellOf(Site site) {
        return new SitePart(PartKey.shellOf(site.id()), "the site itself", "", false, environmentIdsOf(site),
                List.of());
    }

    private static List<String> environmentIdsOf(Site site) {
        return site.environments().stream().map(SiteEnvironment::id).toList();
    }

    /**
     * The one part that carries this system, whichever environment the change is in - which is the whole point
     * of this axis: an upload names no environment, and with a part per system it does not have to.
     */
    @Override
    public List<SitePart> partsDocumenting(Site site, String environment, String systemName) {
        String slug;
        try {
            slug = Slugs.toSlug(systemName);
        } catch (IllegalArgumentException e) {
            // A name made of nothing but punctuation has no slug and therefore no page. The import refuses
            // such a name outright, so this is the upload path - and the answer is that no part carries it,
            // rather than a trigger that fails an upload which is already stored.
            log.warn("The system name '{}' yields no slug, so no part of the site {} documents it: {}",
                    systemName, site.id(), e.getMessage());
            return List.of();
        }
        return List.of(systemPart(site, slug));
    }

    /**
     * One system, in every environment of the site. The prefixes are what the resolver matches a request
     * against and what the part's Docusaurus build mounts its trees at, so the two cannot drift apart.
     */
    private SitePart systemPart(Site site, String systemSlug) {
        String path = DocumentationPaths.system(systemSlug);
        List<String> prefixes = site.environments().stream()
                .map(environment -> environment.routePrefix() + path)
                .toList();
        // The tree without its slashes: it is a path inside an environment's content and inside its URL, and
        // both are built from it.
        String tree = path.substring(1, path.length() - 1);
        return new SitePart(PartKey.of(site.id(), SYSTEM_PREFIX + systemSlug),
                "the system " + systemSlug, tree, true, environmentIdsOf(site), prefixes);
    }

    /**
     * Every system slug of the site: from every environment that reads an architecture model, and from what
     * has been documented. Sorted and without duplicates, so two runs produce the same parts in the same
     * order and a system that is both documented and deployed is one part.
     */
    private SortedSet<String> systemSlugsOf(Site site) {
        SortedSet<String> slugs = new TreeSet<>();
        for (SiteEnvironment environment : site.environments()) {
            if (architectureModel.isConfiguredFor(environment.id())) {
                slugs.addAll(architectureModel.systemSlugsOf(environment.id()));
            }
        }
        for (CustomSubject documented : documentation.subjectsOf(site.id())) {
            slugs.add(documented.system());
        }
        return slugs;
    }
}
