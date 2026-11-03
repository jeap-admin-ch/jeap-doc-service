package ch.admin.bit.jeap.doc.governance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The payloads of the Governance Service's {@code /docs-api}.
 * <p>
 * Field names are copied from the records the Governance Service ships, so that binding needs no mapping of
 * its own. The index entries are what the import reads to decide what to fetch; {@code SystemDto} and the
 * records below it are what a page is written from - read back from the stored body by {@link
 * GovernanceResourceContentAdapter}, not parsed at import time.
 */
final class GovernanceDtos {

    private GovernanceDtos() {
    }

    record SystemIndexDto(OffsetDateTime scoredAt, int trendPeriodDays, List<SystemIndexEntryDto> systems) {
    }

    record SystemIndexEntryDto(String name, Integer score, String trend, String etag, String url) {
    }

    record RuleIndexDto(OffsetDateTime scoredAt, List<RuleIndexEntryDto> rules) {
    }

    record RuleIndexEntryDto(String ruleId, String name, boolean active, Integer weight, BigDecimal share,
                             Integer conformanceRate, String trend, Integer exemptionCount, String etag,
                             String url) {
    }

    /** The system resource behind one entry of the systems index. */
    record SystemDto(String name, Integer score, String trend, List<DailyScoreDto> history,
                     List<ComponentDto> components) {
    }

    record ComponentDto(String name, Integer score, String trend, List<DailyScoreDto> history,
                        List<RuleStateDto> ruleStates) {
    }

    record RuleStateDto(String ruleId, String name, String state, String comment, List<String> details,
                        OffsetDateTime lastStatusChange) {
    }

    record DailyScoreDto(LocalDate day, int score) {
    }

    /** The rule resource behind one entry of the rules index. */
    record RuleDto(String ruleId, String name, boolean active, String explanation, Integer weight,
                   BigDecimal share, String violationDelay, boolean gracePeriodConfigured,
                   List<RuleParameterDto> parameters, List<RuleExemptionDto> exemptions, Integer conformanceRate,
                   String trend, List<RuleSystemRateDto> systems,
                   List<NonCompliantComponentDto> nonCompliantComponents,
                   List<GracePeriodComponentDto> gracePeriodComponents) {
    }

    record RuleParameterDto(String name, String value) {
    }

    record RuleExemptionDto(String id, String component, String reason, LocalDate until, String effect,
                            List<RuleParameterDto> parameters) {
    }

    /** One entry of a rule's conformance rate per system. */
    record RuleSystemRateDto(String system, Integer conformanceRate) {
    }

    record NonCompliantComponentDto(String system, String component, LocalDate since) {
    }

    record GracePeriodComponentDto(String system, String component, String comment, List<String> details,
                                   OffsetDateTime violationDetectedAt, OffsetDateTime gracePeriodEndsAt) {
    }
}
