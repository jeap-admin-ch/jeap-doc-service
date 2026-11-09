package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRule;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRulesIndex;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystem;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemSummary;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemsIndex;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a stored resource's bytes into what a page is written from - see {@code GovernanceContentWriterTest}
 * for what the site generator does with the result.
 */
class GovernanceResourceContentAdapterTest {

    private final GovernanceResourceContentAdapter content =
            new GovernanceResourceContentAdapter(JsonMapper.builder().build());

    @Test
    void systemsIndex_thenItIsRead() {
        Optional<GovernanceSystemsIndex> index = content.systemsIndex(bytes("""
                {
                  "scoredAt": "2026-11-04T06:00:00Z",
                  "trendPeriodDays": 30,
                  "systems": [
                    {"name": "orders", "score": 73, "trend": "DOWN", "etag": "\\"e1\\"", "url": "/systems/orders"}
                  ]
                }
                """));

        assertThat(index).contains(new GovernanceSystemsIndex(OffsetDateTime.parse("2026-11-04T06:00:00Z"), 30,
                List.of(new GovernanceSystemSummary("orders", 73, "DOWN"))));
    }

    @Test
    void systemsIndex_whenTheListIsAbsent_thenItIsReadAsEmpty() {
        Optional<GovernanceSystemsIndex> index = content.systemsIndex(bytes("""
                {"scoredAt": "2026-11-04T06:00:00Z", "trendPeriodDays": 30}
                """));

        assertThat(index).contains(new GovernanceSystemsIndex(OffsetDateTime.parse("2026-11-04T06:00:00Z"), 30,
                List.of()));
    }

    @Test
    void systemsIndex_whenTheBodyIsMalformed_thenItIsLeftOutRatherThanThrowing() {
        assertThat(content.systemsIndex(bytes("{not json"))).isEmpty();
    }

    @Test
    void systemsIndex_whenTheBodyIsTheWrongShape_thenItIsLeftOutRatherThanThrowing() {
        assertThat(content.systemsIndex(bytes("""
                {"scoredAt": "2026-11-04T06:00:00Z", "trendPeriodDays": "not a number"}
                """))).isEmpty();
    }

    @Test
    void system_thenItIsReadWithItsHistoryAndComponents() {
        Optional<GovernanceSystem> system = content.system(bytes("""
                {
                  "name": "orders",
                  "score": 70,
                  "trend": "STABLE",
                  "history": [{"day": "2026-11-01", "score": 68}, {"day": "2026-11-02", "score": 70}],
                  "components": [
                    {
                      "name": "orders-payment-scs",
                      "score": 65,
                      "trend": "UP",
                      "history": [],
                      "ruleStates": [
                        {
                          "ruleId": "naming-convention",
                          "name": "Naming convention",
                          "state": "FAIL",
                          "comment": "Not named after the system",
                          "details": ["detail one"],
                          "lastStatusChange": "2026-11-01T06:00:00Z"
                        }
                      ]
                    }
                  ]
                }
                """));

        assertThat(system).isPresent();
        GovernanceSystem value = system.get();
        assertThat(value.name()).isEqualTo("orders");
        assertThat(value.score()).isEqualTo(70);
        assertThat(value.trend()).isEqualTo("STABLE");
        assertThat(value.history()).hasSize(2);
        assertThat(value.history().get(0).day()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(value.history().get(0).score()).isEqualTo(68);

        assertThat(value.components()).hasSize(1);
        GovernanceComponent component = value.components().get(0);
        assertThat(component.name()).isEqualTo("orders-payment-scs");
        assertThat(component.ruleStates()).hasSize(1);
        GovernanceRuleState state = component.ruleStates().get(0);
        assertThat(state.ruleId()).isEqualTo("naming-convention");
        assertThat(state.state()).isEqualTo("FAIL");
        assertThat(state.comment()).isEqualTo("Not named after the system");
        assertThat(state.details()).containsExactly("detail one");
        assertThat(state.lastStatusChange()).isEqualTo(OffsetDateTime.parse("2026-11-01T06:00:00Z"));
    }

    @Test
    void system_whenThereAreNoComponents_thenTheyAreReadAsEmpty() {
        Optional<GovernanceSystem> system = content.system(bytes("""
                {"name": "orders", "score": 70, "trend": "STABLE"}
                """));

        assertThat(system).isPresent();
        assertThat(system.get().history()).isEmpty();
        assertThat(system.get().components()).isEmpty();
    }

    @Test
    void system_whenARuleStateHasNoDetails_thenTheyAreReadAsEmptyRatherThanNull() {
        Optional<GovernanceSystem> system = content.system(bytes("""
                {
                  "name": "orders",
                  "score": 70,
                  "trend": "STABLE",
                  "components": [
                    {
                      "name": "orders-payment-scs",
                      "score": 65,
                      "trend": "UP",
                      "ruleStates": [
                        {"ruleId": "naming-convention", "name": "Naming convention", "state": "OK"}
                      ]
                    }
                  ]
                }
                """));

        assertThat(system).isPresent();
        assertThat(system.get().components().get(0).ruleStates().get(0).details()).isEmpty();
    }

    @Test
    void system_whenTheBodyIsMalformed_thenItIsLeftOutRatherThanThrowing() {
        assertThat(content.system(bytes("not json at all"))).isEmpty();
    }

    @Test
    void rulesIndex_thenItIsRead() {
        Optional<GovernanceRulesIndex> index = content.rulesIndex(bytes("""
                {
                  "scoredAt": "2026-11-03T10:47:00Z",
                  "rules": [
                    {"ruleId": "naming-convention", "name": "Naming convention", "active": true, "weight": 10,
                     "share": 50.0, "conformanceRate": 92, "trend": "STABLE", "exemptionCount": 0,
                     "etag": "\\"e1\\"", "url": "/rules/naming-convention"},
                    {"ruleId": "component-produces-metrics", "name": "Component produces metrics",
                     "active": false, "weight": null, "share": null, "conformanceRate": null,
                     "trend": "NO_DATA", "exemptionCount": null, "etag": "\\"e2\\"",
                     "url": "/rules/component-produces-metrics"}
                  ]
                }
                """));

        assertThat(index).isPresent();
        assertThat(index.get().scoredAt()).isEqualTo(OffsetDateTime.parse("2026-11-03T10:47:00Z"));
        assertThat(index.get().rules()).hasSize(2);
        assertThat(index.get().rules().get(0).ruleId()).isEqualTo("naming-convention");
        assertThat(index.get().rules().get(0).active()).isTrue();
        assertThat(index.get().rules().get(0).weight()).isEqualTo(10);
        assertThat(index.get().rules().get(1).ruleId()).isEqualTo("component-produces-metrics");
        assertThat(index.get().rules().get(1).active()).isFalse();
        assertThat(index.get().rules().get(1).weight()).isNull();
    }

    @Test
    void rulesIndex_whenTheBodyIsMalformed_thenItIsLeftOutRatherThanThrowing() {
        assertThat(content.rulesIndex(bytes("{not json"))).isEmpty();
    }

    @Test
    void rule_whenActive_thenItIsReadWithItsExplanationWeightDelayParametersAndConformance() {
        Optional<GovernanceRule> rule = content.rule(bytes("""
                {
                  "ruleId": "naming-convention",
                  "name": "Component naming convention",
                  "active": true,
                  "explanation": "# Component naming convention\\n\\nChecks the name.",
                  "weight": 10,
                  "share": 50.0,
                  "violationDelay": "PT168H",
                  "gracePeriodConfigured": true,
                  "parameters": [{"name": "ignored-service-names", "value": "legacy-gateway"}],
                  "exemptions": [
                    {"id": "exemption-1", "component": "legacy-gateway", "reason": "Migration in progress",
                     "until": "2026-12-31", "effect": "PAUSED", "parameters": []}
                  ],
                  "conformanceRate": 92,
                  "trend": "STABLE",
                  "systems": [{"system": "orders", "conformanceRate": 95}],
                  "nonCompliantComponents": [
                    {"system": "orders", "component": "orders-legacy", "since": "2026-03-03"}
                  ],
                  "gracePeriodComponents": [
                    {"system": "orders", "component": "orders-legacy", "comment": "Migration planned",
                     "details": ["tracked in JEAP-1", "- first\\n* second\\n\\nthird"], "violationDetectedAt": "2026-10-20T06:00:00Z",
                     "gracePeriodEndsAt": "2026-11-10T06:00:00Z"}
                  ]
                }
                """));

        assertThat(rule).isPresent();
        GovernanceRule value = rule.get();
        assertThat(value.ruleId()).isEqualTo("naming-convention");
        assertThat(value.active()).isTrue();
        assertThat(value.explanation()).contains("Checks the name.");
        assertThat(value.weight()).isEqualTo(10);
        assertThat(value.violationDelay()).isEqualTo(Duration.ofHours(168));
        assertThat(value.gracePeriodConfigured()).isTrue();

        assertThat(value.parameters()).hasSize(1);
        assertThat(value.parameters().get(0).name()).isEqualTo("ignored-service-names");
        assertThat(value.parameters().get(0).value()).isEqualTo("legacy-gateway");

        assertThat(value.exemptions()).hasSize(1);
        assertThat(value.exemptions().get(0).id()).isEqualTo("exemption-1");
        assertThat(value.exemptions().get(0).until()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(value.exemptions().get(0).effect()).isEqualTo("PAUSED");

        assertThat(value.conformanceRate()).isEqualTo(92);
        assertThat(value.systems()).hasSize(1);
        assertThat(value.systems().get(0).system()).isEqualTo("orders");
        assertThat(value.systems().get(0).rate()).isEqualTo(95);

        assertThat(value.nonCompliantComponents()).hasSize(1);
        assertThat(value.nonCompliantComponents().get(0).component()).isEqualTo("orders-legacy");
        assertThat(value.nonCompliantComponents().get(0).since()).isEqualTo(LocalDate.parse("2026-03-03"));

        assertThat(value.gracePeriodComponents()).hasSize(1);
        assertThat(value.gracePeriodComponents().get(0).comment()).isEqualTo("Migration planned");
        assertThat(value.gracePeriodComponents().get(0).details())
                .containsExactly("tracked in JEAP-1", "first", "second", "third");
        assertThat(value.gracePeriodComponents().get(0).detectedAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-20T06:00:00Z"));
    }

    @Test
    void rule_whenInactive_thenItsExplanationIsReadAndEverythingElseIsNullOrEmpty() {
        Optional<GovernanceRule> rule = content.rule(bytes("""
                {
                  "ruleId": "component-produces-metrics",
                  "name": "Component produces metrics",
                  "active": false,
                  "explanation": "# Component produces metrics\\n\\nChecks for metrics.",
                  "weight": null,
                  "share": null,
                  "violationDelay": null,
                  "gracePeriodConfigured": false,
                  "parameters": [],
                  "exemptions": [],
                  "conformanceRate": null,
                  "trend": "NO_DATA",
                  "systems": [],
                  "nonCompliantComponents": [],
                  "gracePeriodComponents": []
                }
                """));

        assertThat(rule).isPresent();
        GovernanceRule value = rule.get();
        assertThat(value.active()).isFalse();
        assertThat(value.explanation()).contains("Checks for metrics.");
        assertThat(value.weight()).isNull();
        assertThat(value.violationDelay()).isNull();
        assertThat(value.conformanceRate()).isNull();
    }

    @Test
    void rule_whenTheExplanationIsNull_thenItIsReadAsNull() {
        Optional<GovernanceRule> rule = content.rule(bytes("""
                {
                  "ruleId": "a-custom-rule",
                  "name": "A custom rule",
                  "active": true,
                  "explanation": null,
                  "weight": 5,
                  "share": 25.0,
                  "violationDelay": null,
                  "gracePeriodConfigured": false,
                  "parameters": [],
                  "exemptions": [],
                  "conformanceRate": 100,
                  "trend": "STABLE",
                  "systems": [],
                  "nonCompliantComponents": [],
                  "gracePeriodComponents": []
                }
                """));

        assertThat(rule).isPresent();
        assertThat(rule.get().explanation()).isNull();
    }

    @Test
    void rule_whenTheViolationDelayIsNotAnIsoDuration_thenItIsLeftOutRatherThanThrowing() {
        assertThat(content.rule(bytes("""
                {
                  "ruleId": "naming-convention",
                  "name": "Component naming convention",
                  "active": true,
                  "violationDelay": "not a duration"
                }
                """))).isEmpty();
    }

    @Test
    void rule_whenTheBodyIsMalformed_thenItIsLeftOutRatherThanThrowing() {
        assertThat(content.rule(bytes("not json at all"))).isEmpty();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
