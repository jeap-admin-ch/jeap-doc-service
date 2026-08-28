package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads what the documentation generator has been doing, for the administration API.
 * <p>
 * The reading lives here rather than in the controller so that the web module keeps its hands off the
 * repositories: everything below {@code …doc.domain.port} is the domain's, and an adapter that reaches past this
 * service would be a second definition of what the state of a site is.
 */
@Service
@RequiredArgsConstructor
public class DocumentationSiteStatus {

    private final DocumentationSites sites;
    private final DocumentationBuildRepository builds;
    private final DocumentationBuildRequestRepository requests;

    /**
     * Every configured site, in the order they are configured.
     * <p>
     * What is pending and what is running is read once for all sites rather than once per site - there is at
     * most one request per site and hardly ever a running build, so both are a handful of rows however many
     * sites an instance serves. What is published and what was built last are still read <b>per site</b>, two
     * indexed single-row queries each: sites are configured rather than discovered, so there are a handful of
     * them, and a query per site is not worth a join to avoid.
     */
    public List<SiteStatus> all() {
        Map<String, BuildRequest> pending = requests.pending().stream()
                .collect(Collectors.toMap(BuildRequest::site, Function.identity(), (first, second) -> first));
        Map<String, List<DocumentationBuild>> running = builds.running().stream()
                .collect(Collectors.groupingBy(DocumentationBuild::site));
        return sites.all().stream()
                .map(site -> statusOf(site, pending.get(site.id()),
                        running.getOrDefault(site.id(), List.of())))
                .toList();
    }

    /**
     * One site, or nothing when the instance does not configure it.
     */
    public Optional<SiteStatus> of(String site) {
        return sites.find(site).map(configured -> statusOf(configured,
                requests.pending().stream()
                        .filter(request -> request.site().equals(configured.id()))
                        .findFirst()
                        .orElse(null),
                builds.running().stream()
                        .filter(build -> build.site().equals(configured.id()))
                        .toList()));
    }

    /**
     * The most recent builds of a site, newest first.
     */
    public List<DocumentationBuild> recentBuilds(String site, int limit) {
        return builds.recent(site, limit);
    }

    /**
     * One build of one site.
     */
    public Optional<DocumentationBuild> build(String site, long id) {
        return builds.find(site, id);
    }

    private SiteStatus statusOf(Site site, BuildRequest pending, List<DocumentationBuild> running) {
        return new SiteStatus(site, pending, running,
                builds.published(site.id()).orElse(null),
                builds.recent(site.id(), 1).stream().findFirst().orElse(null));
    }
}
