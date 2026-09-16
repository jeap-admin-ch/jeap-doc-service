package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The documentation as it is served: which publication holds a path, and the files of it.
 * <p>
 * A site is published as several builds, one per part, so a path is served out of <b>the publication of the part
 * that owns it</b> - the most specific one, exactly as the site resolver picks a site. The shell part owns
 * whatever no other part claims, and the shared files of a site (see {@link SharedAssets}) come from one prefix
 * of their own.
 * <p>
 * What is published for a part is the newest successful build of it - there is no second place saying so, and
 * therefore no second place that could disagree with it. It is looked up rather than pushed, and cached for a
 * few seconds, so that an instance picks up what another instance published without asking the database for
 * every file of every page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishedDocumentation {

    private final DisplayReads reads;
    private final DocumentationSites sites;
    private final SitePartition partition;
    private final SitePublicationStorage storage;
    private final PublicationProperties properties;
    private final Clock clock;

    private final Map<String, CachedParts> published = new ConcurrentHashMap<>();

    /**
     * Reads one file of a site's published documentation, if the part that owns it has been published and holds
     * it.
     *
     * @param site the site being served
     * @param path the path of the file within that site, without a leading slash
     */
    public Optional<StoredObject> open(String site, String path) {
        for (String prefix : prefixesOf(site, path)) {
            Optional<StoredObject> object = storage.open(prefix, path);
            if (object.isPresent()) {
                return object;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether one file of a site's published documentation is there, without opening it - for a caller that only
     * wants to know, and would otherwise leak the connection {@link #open} hands it.
     */
    public boolean exists(String site, String path) {
        return prefixesOf(site, path).stream().anyMatch(prefix -> storage.exists(prefix, path));
    }

    /**
     * Whether anything has been published for the given site.
     * <p>
     * <b>The shell part decides.</b> It is the part that carries the site's own pages, so a site whose shell has
     * never been built has no front page, no systems index and no navigation - and a site that has never been
     * built is answered differently from a page that does not exist: one is a service that is not ready, the
     * other is a wrong URL.
     */
    public boolean isPublished(String site) {
        return partsOf(site).prefixByPart.containsKey(SitePart.SHELL);
    }

    /**
     * The prefixes a path of a site is served from, in the order to try them: the current search index for its
     * files, the shared prefix for the shared files, and the publication of the most specific part that owns
     * the path - the shell where none does.
     * <p>
     * <b>The search index comes first, and it is not one of the shared files.</b> Those are written by every
     * part build under the rule that each writes the same bytes; an index has one writer, one lifecycle and an
     * identifier of its own, so it is a prefix of its own and it has no fallback - a file it does not hold is
     * a file no other prefix holds either.
     * <p>
     * <b>A shared file falls back to the owning part's publication, and that is what makes an upgrade
     * seamless.</b> Before this service published a site in parts it wrote <i>every</i> file of a build under
     * that build's own prefix, shared or not, and the migration keeps that publication serving as the shell -
     * so its pages are answered from it while the parts are built. Their stylesheets, scripts and images,
     * however, are under that same old prefix and not under the site's shared one, which no build has written
     * yet. Resolving a shared path to the shared prefix alone would answer every one of them with a 404: the
     * pages of the site would arrive without their layout until the shell had been rebuilt, which is the last
     * part of the first pass.
     * <p>
     * <b>Which in practice means the shell.</b> No part claims a top-level {@code assets/} route, so the most
     * specific part owning one is always the shell - and the shell is the part the old whole-site publication
     * is kept as. The fallback is reached only when the shared prefix does not hold the file, so it costs one
     * lookup on paths that would otherwise be a 404 and nothing at all on the ordinary path.
     */
    private List<String> prefixesOf(String site, String path) {
        CachedParts parts = partsOf(site);
        if (SearchIndex.holds(path)) {
            // One prefix and no fallback: an index is whole or it is not there.
            return parts.searchIndexPrefix == null ? List.of() : List.of(parts.searchIndexPrefix);
        }
        if (parts.prefixByPart.isEmpty()) {
            return List.of();
        }
        Optional<String> owning = parts.owning(path);
        if (!SharedAssets.holds(path)) {
            return owning.map(List::of).orElseGet(List::of);
        }
        String shared = SharedAssets.prefixOf(site);
        return owning.filter(prefix -> !prefix.equals(shared))
                .map(prefix -> List.of(shared, prefix))
                .orElseGet(() -> List.of(shared));
    }

    /**
     * What is published for each part of a site, and which paths each of them owns.
     * <p>
     * Both halves are cached together for the same few seconds: they are read on every request that the cache
     * does not answer, and a partition that disagreed with the publications it was matched against would serve
     * one part's page out of another's publication.
     */
    private CachedParts partsOf(String site) {
        CachedParts cached = published.get(site);
        Instant now = clock.instant();
        if (cached != null && cached.readAt.plus(properties.getRefresh()).isAfter(now)) {
            return cached;
        }
        CachedParts read = read(site, now);
        if (cached == null || cached.prefixByPart.size() != read.prefixByPart.size()) {
            log.info("The documentation site {} is now served from {} published part(s).",
                    site, read.prefixByPart.size());
        }
        published.put(site, read);
        return read;
    }

    private CachedParts read(String site, Instant now) {
        Map<String, String> prefixByPart = new HashMap<>();
        List<SitePart> owners = new ArrayList<>();
        Optional<Site> configured = sites.find(site);
        for (PublishedPart part : reads.publishedPartsOf(site)) {
            if (part.objectPrefix() == null) {
                // Published once and since expired by the retention. There is nothing to serve from it, and
                // treating it as published would answer 404 for every page instead of "not generated yet".
                continue;
            }
            prefixByPart.put(part.part(), part.objectPrefix());
            configured.flatMap(each -> partition.partOf(each, part.part()))
                    .filter(each -> !each.isShell())
                    .ifPresent(owners::add);
        }
        // The most specific first, so that the part owning the longest matching prefix answers. Without it a
        // site whose parts nest would be served by whichever one the database listed first.
        owners.sort(Comparator.comparingInt(SitePart::specificity).reversed());
        // Read here rather than per request, and cached for the same few seconds as the parts: a page of the
        // site and the index it searches should not be minutes apart in what they were read from.
        String searchIndex = reads.currentSearchIndexOf(site)
                .map(PublishedSearchIndex::objectPrefix).orElse(null);
        return new CachedParts(prefixByPart, List.copyOf(owners), searchIndex, now);
    }

    /**
     * What was published last time it was looked up, which parts own what, and when that was.
     *
     * @param prefixByPart      where each published part's files are
     * @param owners            the published parts that claim paths, most specific first. The shell is not
     *                          among them: it is what answers when none of these does
     * @param searchIndexPrefix where the current search index is, or null while a site has never been indexed
     */
    private record CachedParts(Map<String, String> prefixByPart, List<SitePart> owners, String searchIndexPrefix,
                               Instant readAt) {

        Optional<String> owning(String path) {
            String withinSite = "/" + path;
            for (SitePart owner : owners) {
                if (owner.owns(withinSite)) {
                    return Optional.ofNullable(prefixByPart.get(owner.id()));
                }
            }
            return Optional.ofNullable(prefixByPart.get(SitePart.SHELL));
        }
    }
}
