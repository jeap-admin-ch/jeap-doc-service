package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One part of a documentation site: what it carries, what is published for it, and whether it is owed a build.
 * <p>
 * A site is published as several Docusaurus builds now, so <i>is this documentation up to date</i> is a
 * question about the parts rather than about one build. The age is what answers it: a part nobody has rebuilt
 * for a week either has not changed for a week or has stopped being built, and the two are told apart by
 * whether any other part of the site is younger.
 *
 * @param part          the identifier of the part within its site
 * @param documents     what it documents, in words
 * @param routePrefixes the URL subtrees it answers for, empty for the part that answers for the remainder
 * @param environments  the environments of the site it carries
 * @param publishedAt   when what is being served for it was built, null while it never has been
 * @param ageSeconds    how long ago that was, null while it has never been published
 * @param contentDigest what the content it was built from hashed to, which is what says whether a build is owed
 * @param owedABuild    whether a build of it is pending right now
 */
@Schema(description = "One part of a documentation site")
record PartDto(
        String part,
        String documents,
        List<String> routePrefixes,
        List<String> environments,
        Instant publishedAt,
        Long ageSeconds,
        String contentDigest,
        boolean owedABuild) {

    static PartDto of(SitePart part, PublishedPart published, boolean owedABuild, Instant now) {
        Instant publishedAt = published == null ? null : published.publishedAt();
        return new PartDto(part.id(), part.documents(), part.routePrefixes(), part.environments(),
                publishedAt,
                publishedAt == null ? null : Duration.between(publishedAt, now).toSeconds(),
                published == null ? null : published.contentDigest(),
                owedABuild);
    }
}
