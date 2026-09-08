package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an operator asks about the parts of a site: what they are, and what is published for each of them.
 * <p>
 * A site is published as several builds, so <i>is this up to date</i> is no longer answered by one row. This is
 * where the pieces are put together: the partition says which parts there are, and the publications say what is
 * being served for each.
 */
@Service
@RequiredArgsConstructor
public class DocumentationParts {

    private final DocumentationSites sites;
    private final SitePartition partition;
    private final DocumentationBuildRepository builds;
    private final DocumentationBuildRequestRepository requests;

    /**
     * Every part of a site, with what is published for it - or empty when this instance configures no such
     * site.
     */
    public Optional<List<PartState>> of(String site) {
        return sites.find(site).map(configured -> {
            Map<String, PublishedPart> published = publishedPartsOf(site);
            List<PartKey> owed = requests.pending().stream().map(BuildRequest::part).toList();
            return partition.partsOf(configured).stream()
                    .map(part -> new PartState(part, published.get(part.id()), owed.contains(part.key())))
                    .toList();
        });
    }

    /**
     * One part of a site, <b>if the site really has one</b>.
     * <p>
     * Looked up among the parts of the site rather than derived from the identifier. The partition can derive a
     * part from any identifier of the right shape, and that is what makes serving a request cheap - but an
     * operator asking for a build of {@code system-nobody-documents} has made a typo, and a request for a part
     * nothing documents would be built into an empty site and then puzzled over.
     */
    public Optional<PartState> of(String site, String partId) {
        return of(site).flatMap(parts -> parts.stream()
                .filter(state -> state.part().id().equals(partId))
                .findFirst());
    }

    /** The builds of one part, newest first. */
    public List<DocumentationBuild> recentBuildsOf(PartKey part, int limit) {
        return builds.recentOf(part, limit);
    }

    private Map<String, PublishedPart> publishedPartsOf(String site) {
        Map<String, PublishedPart> published = new HashMap<>();
        builds.publishedPartsOf(site).forEach(part -> published.put(part.part(), part));
        return published;
    }

    /**
     * One part as an operator reads it: what it is, what is published for it, and whether a build of it is
     * pending.
     *
     * @param part       what the partition says this part is
     * @param published  what is being served for it, null while nothing is
     * @param owedABuild whether a build of it is pending right now
     */
    public record PartState(SitePart part, PublishedPart published, boolean owedABuild) {
    }
}
