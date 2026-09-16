package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
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
    private final DisplayReads reads;

    /**
     * Every configured site, in the order they are configured.
     * <p>
     * What is pending and what is running is read once for all sites rather than once per site. There is one
     * request per <b>part</b> now, so a site fed by the hourly import has as many as it has systems; still a
     * few hundred rows however many sites an instance serves, and read as one statement. Of the requests of a
     * site the <b>oldest</b> is the one shown, which is what says how long anything has been waiting. What is
     * published and what was built last are still read <b>per site</b>, two indexed single-row queries each:
     * sites are configured rather than discovered, so there are a handful of them.
     */
    public List<SiteStatus> all() {
        Map<String, BuildRequest> pending = reads.pendingRequests().stream()
                .collect(Collectors.toMap(BuildRequest::site, Function.identity(), (first, second) -> first));
        Map<String, List<DocumentationBuild>> running = reads.runningBuilds().stream()
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
                reads.pendingRequests().stream()
                        .filter(request -> request.site().equals(configured.id()))
                        .findFirst()
                        .orElse(null),
                reads.runningBuilds().stream()
                        .filter(build -> build.site().equals(configured.id()))
                        .toList()));
    }

    /**
     * The most recent builds of a site, newest first.
     */
    public List<DocumentationBuild> recentBuilds(String site, int limit) {
        return reads.recentBuilds(site, limit);
    }

    /**
     * One build of one site.
     */
    public Optional<DocumentationBuild> build(String site, long id) {
        return reads.build(site, id);
    }

    /**
     * <b>{@code published} is the shell's publication and not the site's.</b> A site is published as several
     * builds and no one of them is <i>the</i> published one; the shell is the part that answers for the site's
     * own pages, so its build is what says the site is being served at all. How the rest of it stands is
     * {@code /parts} - a site whose shell publishes and whose systems all fail is a healthy shell.
     */
    private SiteStatus statusOf(Site site, BuildRequest pending, List<DocumentationBuild> running) {
        return new SiteStatus(site, pending, running,
                reads.publishedBuild(PartKey.shellOf(site.id())).orElse(null),
                reads.recentBuilds(site.id(), 1).stream().findFirst().orElse(null));
    }
}
