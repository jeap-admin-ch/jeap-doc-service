package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a microsite's files are, looked up once in a while rather than per request - and never without a bound. */
class PublishedMicrositesTest {

    private static final CustomSetKey PUBLISHED = keyOf("reference");

    private final AtomicInteger queries = new AtomicInteger();

    private final PublishedMicrosites microsites = new PublishedMicrosites(new NoCustomDocumentation() {
        @Override
        public Optional<CustomSet> find(CustomSetKey key) {
            queries.incrementAndGet();
            return key.equals(PUBLISHED) ? Optional.of(setOf(key)) : Optional.empty();
        }
    }, new PublicationProperties(), Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void aPublishedMicrosite_isLookedUpOnceForManyRequests() {
        for (int i = 0; i < 20; i++) {
            assertThat(microsites.prefixOf(PUBLISHED)).contains("current/docs/1/1/files/");
        }

        assertThat(queries).hasValue(1);
    }

    /** A dead deep link loads a page and its twenty assets, and that is one query rather than twenty. */
    @Test
    void aMicrositeThatIsNotThere_isCachedToo() {
        for (int i = 0; i < 20; i++) {
            assertThat(microsites.prefixOf(keyOf("gone"))).isEmpty();
        }

        assertThat(queries).hasValue(1);
    }

    /**
     * <b>Made-up paths cost queries, never memory.</b> The key of a miss is whatever a request put in its path,
     * and microsites are served to anyone - so a loop over made-up paths grew the heap without end.
     */
    @Test
    void manyMadeUpPaths_keepTheCacheBounded() {
        for (int i = 0; i < 3 * PublishedMicrosites.MAX_MISSES; i++) {
            microsites.prefixOf(keyOf("made-up-" + i));
        }

        assertThat(microsites.cachedMisses()).isLessThanOrEqualTo(PublishedMicrosites.MAX_MISSES);
    }

    /** And the flood does not take the prefixes real readers are using with it. */
    @Test
    void manyMadeUpPaths_leaveAPublishedMicrositeCached() {
        microsites.prefixOf(PUBLISHED);
        for (int i = 0; i < 3 * PublishedMicrosites.MAX_MISSES; i++) {
            microsites.prefixOf(keyOf("made-up-" + i));
        }
        int before = queries.get();

        assertThat(microsites.prefixOf(PUBLISHED)).isPresent();
        assertThat(queries).hasValue(before);
    }

    private static CustomSetKey keyOf(String topic) {
        return new CustomSetKey(Site.DEFAULT_SITE, SubjectKind.SYSTEM, "jme", null, SourceFormat.HTML, "arc42",
                "8-crosscutting-concepts", topic);
    }

    private static CustomSet setOf(CustomSetKey key) {
        return new CustomSet(1L, key, "Reference", 1L, "current/docs/1/1/files/", "abc", 10,
                new CustomProvenance("repo", "main", "abc", Instant.EPOCH, null, Instant.EPOCH), List.of());
    }
}
