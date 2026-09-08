package ch.admin.bit.jeap.doc.domain.port;

import java.time.Instant;

/**
 * What is currently published for one part of a site: where its files are, and what they were made of.
 * <p>
 * It is the newest successful build of that part, read as a projection rather than as the whole row - this is
 * asked while serving requests, to decide which part's publication holds a path.
 *
 * @param part          the identifier of the part within its site
 * @param objectPrefix  where its files lie, null once they have been removed by the retention
 * @param publishedAt   when the build that produced it finished
 * @param contentDigest what its content hashed to, which is what says whether a build is owed
 * @param pageCount     how many pages that build produced, which is the best estimate there is of what
 *                      building this part costs again - a build is about thirty milliseconds a page
 */
public record PublishedPart(String part, String objectPrefix, Instant publishedAt, String contentDigest,
                            int pageCount) {
}
