package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
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

    @Override
    public String axis() {
        return "system";
    }

    /**
     * The shell, then one part per system slug the model of any environment knows, in alphabetical order.
     * <p>
     * The union across the environments and not one part per environment and system: a system deployed on dev
     * only is one part all the same, with one of its trees empty.
     */
    @Override
    public List<SitePart> partsOf(Site site) {
        List<SitePart> parts = new java.util.ArrayList<>();
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
        List<String> prefixes = site.environments().stream()
                .map(environment -> environment.routePrefix() + DocumentationPaths.system(systemSlug))
                .toList();
        // The tree without its slashes: it is a path inside an environment's content and inside its URL, and
        // both are built from it.
        String tree = DocumentationPaths.system(systemSlug).substring(1, DocumentationPaths.system(systemSlug).length() - 1);
        return new SitePart(PartKey.of(site.id(), SYSTEM_PREFIX + systemSlug),
                "the system " + systemSlug, tree, true, environmentIdsOf(site), prefixes);
    }

    /**
     * Every system slug of the site, from every environment that reads an architecture model. Sorted and
     * without duplicates, so two runs produce the same parts in the same order.
     */
    private java.util.SortedSet<String> systemSlugsOf(Site site) {
        java.util.SortedSet<String> slugs = new TreeSet<>();
        for (SiteEnvironment environment : site.environments()) {
            if (architectureModel.isConfiguredFor(environment.id())) {
                slugs.addAll(architectureModel.systemSlugsOf(environment.id()));
            }
        }
        return slugs;
    }
}
