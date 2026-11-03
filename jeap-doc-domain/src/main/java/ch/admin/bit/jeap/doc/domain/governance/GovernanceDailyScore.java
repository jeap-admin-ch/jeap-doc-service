package ch.admin.bit.jeap.doc.domain.governance;

import java.time.LocalDate;

/** One day of a score history, as the Governance Service delivers it: oldest first across the trend period. */
public record GovernanceDailyScore(LocalDate day, int score) {
}
