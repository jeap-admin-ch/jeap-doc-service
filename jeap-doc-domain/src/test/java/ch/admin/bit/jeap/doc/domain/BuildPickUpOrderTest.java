package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order an instance takes the parts owed a build in.
 * <p>
 * It is the whole of what keeps two instances off the same part. They share nothing but the database, and the
 * fifty requests of a full publication are written with one instant - so an order that is a function of the
 * requests alone puts every instance on the same part, and all but one of them lose its lock.
 */
class BuildPickUpOrderTest {

    private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;

    /**
     * A part asked for earlier is built earlier, whatever the shuffle does within a second: an upload must not
     * be overtaken for ever by a landscape that is imported every hour.
     */
    @Test
    void of_thenTheOlderSecondComesFirst() {
        List<BuildRequest> pending = List.of(
                request("system-late", NOW),
                request("system-early", NOW.minusSeconds(3600)),
                request("system-later", NOW.plusSeconds(30)));

        List<PartKey> order = partsOf(BuildPickUpOrder.of(pending, allTheSameSize(), new Random(1)));

        assertThat(order).containsExactly(part("system-early"), part("system-late"), part("system-later"));
    }

    /**
     * The case that matters: everything asked for at once, which is what a full publication and an import both
     * look like. Two instances then have to pick different parts, and nothing tells them which.
     */
    @Test
    void of_whenEverythingWasAskedForAtOnce_thenTwoInstancesTakeADifferentOrder() {
        List<BuildRequest> pending = new ArrayList<>();
        for (int system = 0; system < 20; system++) {
            pending.add(request("system-" + system, NOW));
        }

        // Two instances differ in nothing but their random source, which is what the shuffle rests on.
        List<PartKey> onOneInstance = partsOf(BuildPickUpOrder.of(pending, allTheSameSize(), new Random(1)));
        List<PartKey> onAnother = partsOf(BuildPickUpOrder.of(pending, allTheSameSize(), new Random(2)));

        assertThat(onOneInstance).isNotEqualTo(onAnother)
                .containsExactlyInAnyOrderElementsOf(onAnother);
    }

    /**
     * The requests of one publication are written one statement at a time, so their instants differ by
     * microseconds. That is not a difference to order by - it would put every instance back on the same part.
     */
    @Test
    void of_whenTheInstantsDifferByMicroseconds_thenTheyAreStillOneSecondAndShuffledTogether() {
        List<BuildRequest> pending = new ArrayList<>();
        for (int system = 0; system < 20; system++) {
            pending.add(request("system-" + system, NOW.plusNanos(system * 1_000L)));
        }

        assertThat(partsOf(BuildPickUpOrder.of(pending, allTheSameSize(), new Random(1))))
                .isNotEqualTo(partsOf(BuildPickUpOrder.of(pending, allTheSameSize(), new Random(2))));
    }

    @Test
    void of_thenEveryPendingRequestIsInTheOrderExactlyOnce() {
        List<BuildRequest> pending = List.of(
                request("system-orders", NOW),
                request("system-shipping", NOW),
                request(SitePart.SHELL, NOW.minusSeconds(5)));

        assertThat(partsOf(BuildPickUpOrder.of(pending, allTheSameSize(), new Random(1))))
                .containsExactlyInAnyOrder(part("system-orders"), part("system-shipping"),
                        part(SitePart.SHELL));
    }

    @Test
    void of_whenNothingIsPending_thenTheOrderIsEmpty() {
        assertThat(BuildPickUpOrder.of(List.of(), allTheSameSize(), new Random(1))).isEmpty();
    }

    /**
     * A pass ends when its last build ends, so the largest part has to start first: one of five thousand pages
     * starting last adds the whole of itself to the wall clock of the pass.
     */
    @Test
    void of_whenOnePartIsMuchLargerThanTheRest_thenItIsTakenFirst() {
        List<BuildRequest> pending = new ArrayList<>();
        Map<String, Integer> pages = new java.util.HashMap<>();
        for (int system = 0; system < 10; system++) {
            pending.add(request("system-" + system, NOW));
            pages.put("system-" + system, 384);
        }
        pending.add(request("system-huge", NOW));
        pages.put("system-huge", 5000);

        List<PartKey> order = partsOf(BuildPickUpOrder.of(pending, pages(pages), new Random(1)));

        assertThat(order).first().isEqualTo(part("system-huge"));
    }

    /**
     * A part nothing has been published for is the biggest there is: nothing is being served for it at all,
     * and how large it is is exactly what nobody knows.
     */
    @Test
    void of_whenAPartHasNeverBeenPublished_thenItIsTakenBeforeTheOnesThatHave() {
        List<BuildRequest> pending = List.of(
                request("system-published", NOW),
                request("system-new", NOW));

        List<PartKey> order = partsOf(BuildPickUpOrder.of(pending, pages(Map.of("system-published", 5000)),
                new Random(1)));

        assertThat(order).containsExactly(part("system-new"), part("system-published"));
    }

    /**
     * The bands are coarse on purpose: parts of a similar size stay in one band, or every instance would order
     * them the same way and go for the same part.
     */
    @Test
    void of_whenTheSizesAreSimilar_thenTheyAreOneBandAndTwoInstancesStillDiffer() {
        List<BuildRequest> pending = new ArrayList<>();
        Map<String, Integer> pages = new java.util.HashMap<>();
        for (int system = 0; system < 20; system++) {
            pending.add(request("system-" + system, NOW));
            // 384 to 403 pages: a fifth of a percent apart, and nothing to order by.
            pages.put("system-" + system, 384 + system);
        }

        assertThat(partsOf(BuildPickUpOrder.of(pending, pages(pages), new Random(1))))
                .isNotEqualTo(partsOf(BuildPickUpOrder.of(pending, pages(pages), new Random(2))));
    }

    /**
     * The shell links into the system parts and answers whatever they do not, so it is taken after them - a
     * shell published first hands a reader links to parts that have not been built yet.
     */
    @Test
    void of_whenTheShellIsAskedForWithTheSystems_thenItIsTakenLast() {
        List<BuildRequest> pending = new ArrayList<>();
        pending.add(request(SitePart.SHELL, NOW));
        for (int system = 0; system < 5; system++) {
            pending.add(request("system-" + system, NOW));
        }

        // Whatever the shuffle does, and whether or not the shell is the largest part of the site.
        for (int seed = 1; seed <= 5; seed++) {
            List<PartKey> order = partsOf(BuildPickUpOrder.of(pending,
                    pages(Map.of(SitePart.SHELL, 5000)), new Random(seed)));
            assertThat(order).last().isEqualTo(part(SitePart.SHELL));
        }
    }

    /** The age of a request beats its size, and it beats the shell's place at the end. */
    @Test
    void of_whenTheShellWasAskedForEarlier_thenItIsStillTakenFirst() {
        List<BuildRequest> pending = List.of(
                request("system-orders", NOW),
                request(SitePart.SHELL, NOW.minusSeconds(3600)));

        List<PartKey> order = partsOf(BuildPickUpOrder.of(pending, allTheSameSize(), new Random(1)));

        assertThat(order).containsExactly(part(SitePart.SHELL), part("system-orders"));
    }

    /** The age of a request beats its size: nothing may be overtaken for ever for being small. */
    @Test
    void of_whenAnOlderRequestIsForASmallPart_thenItIsStillTakenFirst() {
        List<BuildRequest> pending = List.of(
                request("system-huge", NOW),
                request("system-tiny", NOW.minusSeconds(3600)));

        List<PartKey> order = partsOf(BuildPickUpOrder.of(pending,
                pages(Map.of("system-huge", 5000, "system-tiny", 3)), new Random(1)));

        assertThat(order).containsExactly(part("system-tiny"), part("system-huge"));
    }

    /**
     * The order carries the requests, because when a part was asked for is what decides whether a pass that
     * has already built it offers it again. These cases are about which part comes first.
     */
    private static List<PartKey> partsOf(List<BuildRequest> order) {
        return order.stream().map(BuildRequest::part).toList();
    }

    /** Nothing is known about any part's size, which is what the first pass after a deployment looks like. */
    private static java.util.function.ToIntFunction<PartKey> allTheSameSize() {
        return part -> 400;
    }

    private static java.util.function.ToIntFunction<PartKey> pages(Map<String, Integer> byPart) {
        return part -> byPart.getOrDefault(part.part(), BuildPickUpOrder.NEVER_PUBLISHED);
    }

    private static BuildRequest request(String part, Instant requestedAt) {
        return new BuildRequest(part(part), requestedAt, BuildTrigger.IMPORT, null, false);
    }

    private static PartKey part(String part) {
        return PartKey.of(SITE, part);
    }
}
