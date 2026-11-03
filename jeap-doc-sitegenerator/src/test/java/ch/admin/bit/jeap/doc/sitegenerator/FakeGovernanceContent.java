package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceRule;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRulesIndex;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystem;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemsIndex;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceContent;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Stands in for the adapter that parses a stored resource's bytes into what a page is written from. */
final class FakeGovernanceContent implements GovernanceResourceContent {

    GovernanceSystemsIndex index;
    GovernanceRulesIndex rulesIndex;
    final Map<String, GovernanceSystem> systemsByKey = new LinkedHashMap<>();
    final Map<String, GovernanceRule> rulesByKey = new LinkedHashMap<>();

    @Override
    public Optional<GovernanceSystemsIndex> systemsIndex(byte[] content) {
        return Optional.ofNullable(index);
    }

    @Override
    public Optional<GovernanceSystem> system(byte[] content) {
        return Optional.ofNullable(systemsByKey.get(new String(content, StandardCharsets.UTF_8)));
    }

    @Override
    public Optional<GovernanceRulesIndex> rulesIndex(byte[] content) {
        return Optional.ofNullable(rulesIndex);
    }

    @Override
    public Optional<GovernanceRule> rule(byte[] content) {
        return Optional.ofNullable(rulesByKey.get(new String(content, StandardCharsets.UTF_8)));
    }
}
