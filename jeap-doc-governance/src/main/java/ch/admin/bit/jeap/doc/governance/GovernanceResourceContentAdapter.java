package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceDailyScore;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceGracePeriodComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceNonCompliantComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRule;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleExemption;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleParameter;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleSummary;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRulesIndex;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystem;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemConformance;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemSummary;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemsIndex;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a stored governance resource's bytes into what a page is written from.
 * <p>
 * This is the only place in this module that reads a system or rule resource's body - the import stores both
 * unparsed, see {@link GovernanceUpstreamAdapter}. A malformed body is logged and left out rather than failing
 * the whole build: it is the same rule {@code ArchRepoArtifactContent} follows for a replicated artifact.
 */
@Slf4j
@RequiredArgsConstructor
class GovernanceResourceContentAdapter implements GovernanceResourceContent {

    private final JsonMapper json;

    @Override
    public Optional<GovernanceSystemsIndex> systemsIndex(byte[] content) {
        try {
            GovernanceDtos.SystemIndexDto dto = json.readValue(content, GovernanceDtos.SystemIndexDto.class);
            List<GovernanceSystemSummary> systems = dto.systems() == null ? List.of()
                    : dto.systems().stream()
                            .map(entry -> new GovernanceSystemSummary(entry.name(), entry.score(),
                                    entry.trend()))
                            .toList();
            return Optional.of(new GovernanceSystemsIndex(dto.scoredAt(), dto.trendPeriodDays(), systems));
        } catch (RuntimeException e) {
            log.warn("The stored systems index could not be read: {} It is left out of the site.",
                    e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<GovernanceSystem> system(byte[] content) {
        try {
            GovernanceDtos.SystemDto dto = json.readValue(content, GovernanceDtos.SystemDto.class);
            List<GovernanceComponent> components = dto.components() == null ? List.of()
                    : dto.components().stream().map(GovernanceResourceContentAdapter::componentOf).toList();
            return Optional.of(new GovernanceSystem(dto.name(), dto.score(), dto.trend(),
                    historyOf(dto.history()), components));
        } catch (RuntimeException e) {
            log.warn("The stored resource of the system '{}' could not be read: {} It is left out of the site.",
                    guessedValue(content, "name"), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<GovernanceRulesIndex> rulesIndex(byte[] content) {
        try {
            GovernanceDtos.RuleIndexDto dto = json.readValue(content, GovernanceDtos.RuleIndexDto.class);
            List<GovernanceRuleSummary> rules = dto.rules() == null ? List.of()
                    : dto.rules().stream()
                            .map(entry -> new GovernanceRuleSummary(entry.ruleId(), entry.name(), entry.active(),
                                    entry.weight(), entry.share(), entry.conformanceRate(), entry.trend(),
                                    entry.exemptionCount()))
                            .toList();
            return Optional.of(new GovernanceRulesIndex(dto.scoredAt(), rules));
        } catch (RuntimeException e) {
            log.warn("The stored rules index could not be read: {} It is left out of the site.", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<GovernanceRule> rule(byte[] content) {
        try {
            GovernanceDtos.RuleDto dto = json.readValue(content, GovernanceDtos.RuleDto.class);
            List<GovernanceSystemConformance> systems = dto.systems() == null ? List.of()
                    : dto.systems().stream()
                            .map(system -> new GovernanceSystemConformance(system.system(), system.conformanceRate()))
                            .toList();
            List<GovernanceNonCompliantComponent> nonCompliantComponents = dto.nonCompliantComponents() == null
                    ? List.of()
                    : dto.nonCompliantComponents().stream()
                            .map(component -> new GovernanceNonCompliantComponent(component.system(),
                                    component.component(), component.since()))
                            .toList();
            List<GovernanceGracePeriodComponent> gracePeriodComponents = dto.gracePeriodComponents() == null
                    ? List.of()
                    : dto.gracePeriodComponents().stream()
                            .map(GovernanceResourceContentAdapter::gracePeriodComponentOf)
                            .toList();
            Duration violationDelay = dto.violationDelay() == null ? null : Duration.parse(dto.violationDelay());
            return Optional.of(new GovernanceRule(dto.ruleId(), dto.name(), dto.active(), dto.explanation(),
                    dto.weight(), dto.share(), violationDelay, dto.gracePeriodConfigured(),
                    parametersOf(dto.parameters()), exemptionsOf(dto.exemptions()), dto.conformanceRate(),
                    dto.trend(), systems, nonCompliantComponents, gracePeriodComponents));
        } catch (RuntimeException e) {
            log.warn("The stored resource of the rule '{}' could not be read: {} It is left out of the site.",
                    guessedValue(content, "ruleId"), e.getMessage());
            return Optional.empty();
        }
    }

    private static GovernanceComponent componentOf(GovernanceDtos.ComponentDto dto) {
        List<GovernanceRuleState> ruleStates = dto.ruleStates() == null ? List.of()
                : dto.ruleStates().stream()
                        .map(state -> new GovernanceRuleState(state.ruleId(), state.name(), state.state(),
                                state.comment(), linesOf(state.details()),
                                state.lastStatusChange()))
                        .toList();
        return new GovernanceComponent(dto.name(), dto.score(), dto.trend(), historyOf(dto.history()),
                ruleStates);
    }

    private static List<GovernanceDailyScore> historyOf(List<GovernanceDtos.DailyScoreDto> history) {
        return history == null ? List.of()
                : history.stream().map(day -> new GovernanceDailyScore(day.day(), day.score())).toList();
    }

    private static List<GovernanceRuleParameter> parametersOf(List<GovernanceDtos.RuleParameterDto> parameters) {
        return parameters == null ? List.of()
                : parameters.stream().map(parameter -> new GovernanceRuleParameter(parameter.name(),
                        parameter.value())).toList();
    }

    private static List<GovernanceRuleExemption> exemptionsOf(List<GovernanceDtos.RuleExemptionDto> exemptions) {
        return exemptions == null ? List.of()
                : exemptions.stream()
                        .map(exemption -> new GovernanceRuleExemption(exemption.id(), exemption.component(),
                                exemption.reason(), exemption.until(), exemption.effect(),
                                parametersOf(exemption.parameters())))
                        .toList();
    }

    private static GovernanceGracePeriodComponent gracePeriodComponentOf(
            GovernanceDtos.GracePeriodComponentDto dto) {
        return new GovernanceGracePeriodComponent(dto.system(), dto.component(), dto.comment(),
                linesOf(dto.details()), dto.violationDetectedAt(), dto.gracePeriodEndsAt());
    }

    /**
     * One item per line. An item the upstream delivers with line breaks, or with a bullet marker of its own,
     * would otherwise run together into one paragraph on the page.
     */
    private static List<String> linesOf(List<String> details) {
        if (details == null) {
            return List.of();
        }
        return details.stream()
                .filter(Objects::nonNull)
                .flatMap(detail -> detail.lines())
                .map(line -> line.strip().replaceFirst("^[-*\u2022]\\s+", ""))
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** Best effort only, for the warning: the value is exactly what did not parse. */
    private static String guessedValue(byte[] content, String key) {
        String text = new String(content, StandardCharsets.UTF_8);
        int at = text.indexOf("\"" + key + "\"");
        return at < 0 ? "?" : text.substring(at, Math.min(text.length(), at + 40));
    }
}
