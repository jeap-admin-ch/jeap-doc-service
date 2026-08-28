package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The documentation as it is served: which build of a site is the published one, and the files of it.
 * <p>
 * What is published is the newest successful build of a site - there is no second place saying so, and therefore
 * no second place that could disagree with it. It is looked up rather than pushed, and cached for a few seconds,
 * so that an instance picks up what another instance published without asking the database for every file of
 * every page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishedDocumentation {

    private final DocumentationBuildRepository builds;
    private final SitePublicationStorage storage;
    private final PublicationProperties properties;
    private final Clock clock;

    private final Map<String, CachedPrefix> published = new ConcurrentHashMap<>();

    /**
     * Reads one file of a site's published documentation, if that site has been published and holds it.
     */
    public Optional<StoredObject> open(String site, String path) {
        return prefixOf(site).flatMap(prefix -> storage.open(prefix, path));
    }

    /**
     * Whether one file of a site's published documentation is there, without opening it - for a caller that only
     * wants to know, and would otherwise leak the connection {@link #open} hands it.
     */
    public boolean exists(String site, String path) {
        return prefixOf(site).map(prefix -> storage.exists(prefix, path)).orElse(false);
    }

    /**
     * Whether anything has been published for the given site. A site that has never been built is answered
     * differently from a page that does not exist: one is a service that is not ready, the other is a wrong URL.
     */
    public boolean isPublished(String site) {
        return prefixOf(site).isPresent();
    }

    private Optional<String> prefixOf(String site) {
        CachedPrefix cached = published.get(site);
        Instant now = clock.instant();
        if (cached != null && cached.readAt.plus(properties.getRefresh()).isAfter(now)) {
            return Optional.ofNullable(cached.prefix);
        }
        String prefix = builds.published(site).map(DocumentationBuild::objectPrefix).orElse(null);
        if (cached == null || !java.util.Objects.equals(cached.prefix, prefix)) {
            log.info("The documentation site {} is now served from {}.", site,
                    prefix == null ? "nothing - it has not been generated yet" : prefix);
        }
        published.put(site, new CachedPrefix(prefix, now));
        return Optional.ofNullable(prefix);
    }

    /** What was published last time it was looked up, and when that was. */
    private record CachedPrefix(String prefix, Instant readAt) {
    }
}
