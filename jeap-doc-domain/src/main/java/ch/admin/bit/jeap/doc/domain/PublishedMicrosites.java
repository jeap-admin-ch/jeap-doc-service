package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the files of an uploaded microsite are, for the requests that serve them.
 * <p>
 * The row of a set names the prefix its files lie under, and a further upload writes a new prefix - so this is
 * looked up rather than pushed, and cached for the same few seconds {@link PublishedDocumentation} caches the
 * published parts for. Without the cache every file of every page of a microsite would be a query.
 * <p>
 * <b>A set that is not there is cached too.</b> A reader who follows a dead deep link would otherwise ask the
 * database once per request, and a frame that loads a page of twenty assets asks twenty times.
 * <p>
 * <b>But not without a bound.</b> The key of a miss is made of whatever a request put in its path, and this is
 * served to anyone, so a loop over made-up paths would otherwise grow the heap until the instance fails. Misses
 * are held apart from the sets that exist, and past {@link #MAX_MISSES} they are dropped: a flood of made-up
 * paths then costs database queries, never memory, and never the cached prefixes real readers are using.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishedMicrosites {

    private final CustomDocumentationRepository documentation;
    private final PublicationProperties properties;
    private final Clock clock;

    /** How many misses are kept at most. Far more than a site has dead links, far less than a heap holds. */
    static final int MAX_MISSES = 10_000;

    /** The sets that exist. Bounded by what was uploaded, not by what was asked for. */
    private final Map<CustomSetKey, CachedPrefix> prefixes = new ConcurrentHashMap<>();

    /** The keys that name no set, and when that was found. */
    private final Map<CustomSetKey, Instant> misses = new ConcurrentHashMap<>();

    /**
     * The prefix the files of this microsite lie under, or empty where no such set is published.
     *
     * @param key the set the request addresses
     */
    public Optional<String> prefixOf(CustomSetKey key) {
        Instant now = clock.instant();
        CachedPrefix cached = prefixes.get(key);
        if (cached != null && isFresh(cached.readAt, now)) {
            return Optional.of(cached.prefix);
        }
        Instant missed = misses.get(key);
        if (missed != null && isFresh(missed, now)) {
            return Optional.empty();
        }
        Optional<String> prefix = documentation.find(key).map(CustomSet::objectKey);
        if (prefix.isPresent()) {
            prefixes.put(key, new CachedPrefix(prefix.get(), now));
            misses.remove(key);
        } else {
            // A set that was removed is a miss now, not a prefix whose files are gone.
            prefixes.remove(key);
            rememberMiss(key, now);
        }
        return prefix;
    }

    private void rememberMiss(CustomSetKey key, Instant now) {
        if (misses.size() >= MAX_MISSES) {
            misses.values().removeIf(at -> !isFresh(at, now));
        }
        if (misses.size() >= MAX_MISSES) {
            // Still full of fresh misses: somebody is making paths up. Forgetting them costs a query each.
            misses.clear();
        }
        misses.put(key, now);
    }

    private boolean isFresh(Instant readAt, Instant now) {
        return readAt.plus(properties.getRefresh()).isAfter(now);
    }

    /** How many misses are held, for the test that says they stay bounded. */
    int cachedMisses() {
        return misses.size();
    }

    /** Where a microsite's files were last time this was looked up, and when that was. */
    private record CachedPrefix(String prefix, Instant readAt) {
    }
}
