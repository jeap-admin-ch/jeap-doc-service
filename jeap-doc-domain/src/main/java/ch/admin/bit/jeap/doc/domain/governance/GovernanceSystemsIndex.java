package ch.admin.bit.jeap.doc.domain.governance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The systems index, parsed from the stored resource: every scored system, and the time the scores are as of.
 *
 * @param scoredAt null before the first completed scoring run
 */
public record GovernanceSystemsIndex(OffsetDateTime scoredAt, int trendPeriodDays,
                                     List<GovernanceSystemSummary> systems) {

    public GovernanceSystemsIndex {
        systems = List.copyOf(systems);
    }
}
