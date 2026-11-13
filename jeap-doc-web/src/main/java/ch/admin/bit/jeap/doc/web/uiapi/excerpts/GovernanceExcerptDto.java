package ch.admin.bit.jeap.doc.web.uiapi.excerpts;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceDailyScore;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceExcerpt;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The governance excerpt as the pages read it. Times are written with the offset they carry, which is this
 * service's, so that a page prints them as the generated pages do; links are prefixed with the context path, so
 * that a page can follow them as they are.
 */
record GovernanceExcerptDto(String system, String link, String rulesOverview, String scoredAt, Integer score,
                            String trend, List<DailyScoreDto> history, List<ComponentDto> components) {

    record ComponentDto(String name, String link, Integer score, String trend, List<DailyScoreDto> history,
                        int compliant, int pausedOrDisabled, List<FailingRuleDto> failingRules) {
    }

    record FailingRuleDto(String ruleId, String label, String comment, List<String> details, String since,
                          String link) {
    }

    record DailyScoreDto(String day, int score) {
    }

    static GovernanceExcerptDto of(GovernanceExcerpt excerpt, String contextPath) {
        return new GovernanceExcerptDto(excerpt.system(), link(contextPath, excerpt.link()),
                link(contextPath, excerpt.rulesOverview()), time(excerpt.scoredAt()), excerpt.score(),
                excerpt.trend(), history(excerpt.history()),
                excerpt.components().stream().map(component -> new ComponentDto(component.name(),
                        link(contextPath, component.link()), component.score(), component.trend(),
                        history(component.history()), component.compliant(), component.pausedOrDisabled(),
                        component.failingRules().stream().map(rule -> new FailingRuleDto(rule.ruleId(),
                                rule.label(), rule.comment(), rule.details(), time(rule.since()),
                                link(contextPath, rule.link()))).toList())).toList());
    }

    private static List<DailyScoreDto> history(List<GovernanceDailyScore> history) {
        return history.stream().map(day -> new DailyScoreDto(day.day().toString(), day.score())).toList();
    }

    private static String time(OffsetDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String link(String contextPath, String path) {
        return path == null ? null : contextPath + path;
    }
}
