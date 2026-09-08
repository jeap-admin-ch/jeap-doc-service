package ch.admin.bit.jeap.doc.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * The order one instance takes the parts that are owed a build in.
 * <p>
 * <b>Between seconds it is the oldest first.</b> A part asked for an hour ago is built before one asked for
 * now, so that an upload cannot be overtaken for ever by a landscape that keeps being imported.
 * <p>
 * <b>Within one second the largest parts go first.</b> A pass over fifty parts ends when its last build ends,
 * so a large part that starts last adds the whole of itself to the wall clock. Size is the pages the part was
 * published with, because a build is about thirty milliseconds a page - and a part that has never been
 * published is treated as the largest there is: nothing is being served for it at all, and how big it is is
 * exactly what nobody knows.
 * <p>
 * <b>The shell of a site goes last.</b> It carries the systems index and the links into the system parts, and
 * it is what answers a path no published part claims. Published before them, it hands a reader links to parts
 * that are not there yet - which is also what an upgrade from a whole-site publication looks like, where the
 * old shell answers the whole site until the new one replaces it.
 * <p>
 * <b>Parts of a similar size are shuffled.</b> Every instance reads the same pending requests in the same
 * order, and a full publication writes all of them with one instant - so a strict order puts all of them on
 * the same part, and every instance but one loses that part's lock. Sizes are therefore banded rather than
 * compared exactly, and within a band the order is this instance's own. That is what puts the instances on
 * different parts without their having to agree on anything.
 */
final class BuildPickUpOrder {

    /** What a part that has never been published counts as: the biggest, so that it is built first. */
    static final int NEVER_PUBLISHED = -1;

    /** The band of the shell, below every real one, so that the shell is taken after the parts it links to. */
    private static final long SHELL = Long.MIN_VALUE;

    private BuildPickUpOrder() {
    }

    /**
     * The pending requests in the order to take them: oldest second first, largest band first within a second,
     * and shuffled within a band.
     * <p>
     * The requests and not just their parts, because <b>when</b> a part was asked for decides whether a pass
     * that has already built it offers it again - see {@code DocumentationBuildRunner.Pass}.
     *
     * @param pagesOf how many pages a part was published with, or {@link #NEVER_PUBLISHED}
     */
    static List<BuildRequest> of(List<BuildRequest> pending, ToIntFunction<PartKey> pagesOf, Random random) {
        Map<Instant, Map<Long, List<BuildRequest>>> bySecondThenBand = new TreeMap<>();
        for (BuildRequest request : pending) {
            bySecondThenBand
                    .computeIfAbsent(secondOf(request), second -> new TreeMap<>(Comparator.reverseOrder()))
                    .computeIfAbsent(bandOf(request.part(), pagesOf), band -> new ArrayList<>())
                    .add(request);
        }
        List<BuildRequest> order = new ArrayList<>(pending.size());
        for (Map<Long, List<BuildRequest>> bands : bySecondThenBand.values()) {
            for (List<BuildRequest> withinABand : bands.values()) {
                Collections.shuffle(withinABand, random);
                order.addAll(withinABand);
            }
        }
        return order;
    }

    /**
     * A second rather than the instant itself: the fifty requests of one publication are written one statement
     * at a time and their instants differ by microseconds, which is not a difference anything should order by.
     */
    private static Instant secondOf(BuildRequest request) {
        return request.requestedAt().truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * The size band of a part: the page count rounded down to a power of two, and the lowest band of all for
     * the shell.
     * <p>
     * Coarse on purpose. What the order is for is that the outliers start first - a part of five thousand pages
     * among parts of four hundred - and not that four hundred pages beat three hundred and ninety: parts of a
     * similar size have to stay in one band, or the instances end up on the same part again.
     */
    private static long bandOf(PartKey part, ToIntFunction<PartKey> pagesOf) {
        if (part.isShell()) {
            return SHELL;
        }
        int pages = pagesOf.applyAsInt(part);
        if (pages < 0) {
            return Long.MAX_VALUE;
        }
        return Long.highestOneBit(pages);
    }
}
