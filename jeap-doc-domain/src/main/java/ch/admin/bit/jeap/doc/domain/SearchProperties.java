package ch.admin.bit.jeap.doc.domain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How the search index of a site is built and kept.
 * <p>
 * <b>There is no schedule here.</b> A site is indexed at the end of the build pass that published it, because
 * that is the first moment at which the pages to index exist - see {@code DocumentationBuildRunner}. What is
 * left to configure is whether this instance indexes at all, how many indexes it keeps, and how long a run may
 * hold its lock.
 */
@Data
@ConfigurationProperties("jeap.doc.search")
public class SearchProperties {

    /**
     * Whether this instance indexes its sites at all.
     * <p>
     * The way out when the image and the service do not ship together: the indexer needs a Pagefind binary
     * that arrives with {@code node_modules}, so a service version can reach an instance whose image is older.
     * Off, and neither the startup check nor a build pass asks for it.
     */
    private boolean enabled = true;

    /**
     * How many published indexes of a site are kept, the current one included.
     * <p>
     * <b>Two is the minimum that works, and the reason is not tidiness.</b> Every file the indexer writes
     * carries a content hash in its name, so a reader whose browser has loaded the manifest of the index that
     * was current a moment ago goes on asking for chunks by names that exist only under that index's prefix.
     * Keeping one would answer their next keystroke with a 404.
     */
    private int retention = 2;

    /**
     * How long the lock of an index run survives an instance that dies holding it.
     * <p>
     * Above what a run takes - the content pass of a whole site plus the indexer - so that a run which is
     * taking its time is not started a second time by another instance.
     */
    private Duration lockLease = Duration.ofMinutes(30);

    /**
     * How long after it started a run still recorded as running is taken to be dead.
     * <p>
     * A run that is alive extends its lock, so it can never be here; what can is a run whose instance was
     * killed between writing its files and recording that it had. Well above {@link #lockLease}, because the
     * cost of guessing wrong is deleting the files of a run that is still writing them.
     */
    private Duration abandonedAfter = Duration.ofHours(2);

    /**
     * How long the record of a failed index run is kept. It is evidence of what went wrong, and that is worth
     * a month rather than for ever.
     */
    private Duration failureRetention = Duration.ofDays(30);

    /**
     * The least a retention may be, for the reason on {@link #retention}.
     */
    static final int MINIMUM_RETENTION = 2;

    /**
     * What an instance may not be configured with. Checked at startup rather than at the first index run: a
     * retention of one is a reader's 404 the next time a site is published, which is not a thing to discover
     * from a support request.
     */
    public void check() {
        if (abandonedAfter.compareTo(lockLease) <= 0) {
            throw new IllegalStateException(("jeap.doc.search.abandoned-after is %s and the lock lease is %s. "
                                             + "A run that is alive holds and extends that lock, so treating "
                                             + "one as abandoned that early would delete the files of a run "
                                             + "that is still writing them.")
                    .formatted(abandonedAfter, lockLease));
        }
        if (retention < MINIMUM_RETENTION) {
            throw new IllegalStateException(("jeap.doc.search.retention is %d. At least %d has to be kept: the "
                                             + "index being served, and the one it replaced, whose chunks a "
                                             + "reader who is already searching is still fetching by name.")
                    .formatted(retention, MINIMUM_RETENTION));
        }
    }
}
