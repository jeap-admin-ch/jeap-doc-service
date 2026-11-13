package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.GovernanceExcerptProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.content.GovernanceContent;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceContent;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The governance excerpt of one system, for the architecture pages that read it live.
 * <p>
 * <b>Answered from what the governance import stored, and never from the Governance Service</b>: the pages ask on
 * every view, and must not depend on another service being up. A scoring run changes what this answers the moment
 * its import has stored it, without a page being rebuilt - which is why the excerpt is read rather than written
 * into the pages.
 * <p>
 * It links into the governance site by the same slugs that site is written with ({@link GovernanceSlugs}), so a
 * link leads to a page the site has, and is left out where the site has none.
 */
@Service
public class GovernanceExcerpts {

    /** How many days of the score history an excerpt carries. */
    public static final int HISTORY_DAYS = 14;

    private static final String FAILING = "FAIL";
    private static final String COMPLIANT = "OK";
    private static final Set<String> PAUSED_OR_DISABLED = Set.of("PAUSED", "DISABLED");

    private final GovernanceResourceRepository resources;
    private final GovernanceResourceContent content;
    private final Clock clock;

    /** The site the links lead into. An excerpt without one would link nowhere, so none is answered then. */
    private final Optional<Site> governanceSite;

    public GovernanceExcerpts(GovernanceResourceRepository resources, GovernanceResourceContent content,
                              DocumentationSites sites, GovernanceExcerptProperties properties, Clock clock) {
        this.resources = resources;
        this.content = content;
        this.clock = clock;
        this.governanceSite = governanceSite(sites, properties.getSite());
    }

    /**
     * @param systemName the system's name as the architecture model has it - the governance data's, matched
     *                   exactly first and ignoring case after that
     * @return empty where no governance site is configured, nothing is stored yet, or the system is not scored
     */
    public Optional<GovernanceExcerpt> ofSystem(String systemName) {
        if (systemName == null || systemName.isBlank()) {
            return Optional.empty();
        }
        if (governanceSite.isEmpty()) {
            return Optional.empty();
        }
        // One snapshot: the index, the system and the rules must be of the same import.
        return resources.readConsistently(() -> read(governanceSite.get(), systemName));
    }

    /**
     * The one governance site, or the one that is named. Which of several the links lead into is not for this
     * service to guess, so that fails the startup.
     */
    private static Optional<Site> governanceSite(DocumentationSites sites, String named) {
        List<Site> governanceSites = sites.all().stream()
                .filter(site -> GovernanceContent.SOURCE_ID.equals(site.content()))
                .toList();
        List<String> ids = governanceSites.stream().map(Site::id).toList();
        if (named != null && !named.isBlank()) {
            return Optional.of(governanceSites.stream().filter(site -> site.id().equals(named)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(("jeap.doc.governance.excerpts.site is '%s', "
                            + "which is not a site with content: %s. Those are: %s.")
                            .formatted(named, GovernanceContent.SOURCE_ID, ids))));
        }
        if (governanceSites.size() > 1) {
            throw new IllegalStateException(("The sites %s all have content: %s. Set "
                    + "jeap.doc.governance.excerpts.site to the one the governance excerpts link into.")
                    .formatted(ids, GovernanceContent.SOURCE_ID));
        }
        return governanceSites.stream().findFirst();
    }

    private Optional<GovernanceExcerpt> read(Site site, String systemName) {
        Optional<GovernanceSystemsIndex> index = resources.find(GovernanceResourceKind.SYSTEMS_INDEX, null)
                .flatMap(resource -> content.systemsIndex(resource.content()));
        if (index.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> scoredName = matching(index.get(), systemName);
        if (scoredName.isEmpty()) {
            return Optional.empty();
        }
        Optional<GovernanceSystem> stored = resources.find(GovernanceResourceKind.SYSTEM, scoredName.get())
                .flatMap(resource -> content.system(resource.content()));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        GovernanceSystem system = stored.get();
        Links links = links(site, index.get(), system);
        return Optional.of(new GovernanceExcerpt(
                system.name(),
                links.system(),
                links.rulesOverview(),
                inOwnZone(index.get().scoredAt()),
                system.score(),
                system.trend(),
                lastDays(system.history()),
                system.components().stream().map(component -> component(component, links)).toList()));
    }

    private static Optional<String> matching(GovernanceSystemsIndex index, String systemName) {
        List<String> names = index.systems().stream().map(GovernanceSystemSummary::name)
                .filter(Objects::nonNull).toList();
        return names.stream().filter(systemName::equals).findFirst()
                .or(() -> names.stream().filter(systemName::equalsIgnoreCase).findFirst());
    }

    private GovernanceExcerpt.Component component(GovernanceComponent component, Links links) {
        List<GovernanceRuleState> states = component.ruleStates();
        List<GovernanceExcerpt.FailingRule> failing = states.stream()
                .filter(state -> FAILING.equals(state.state()))
                .map(state -> new GovernanceExcerpt.FailingRule(state.ruleId(), links.labelOf(state),
                        state.comment(), state.details(), inOwnZone(state.lastStatusChange()),
                        links.rule(state.ruleId())))
                .toList();
        return new GovernanceExcerpt.Component(
                component.name(),
                links.component(component.name()),
                component.score(),
                component.trend(),
                lastDays(component.history()),
                (int) states.stream().filter(state -> COMPLIANT.equals(state.state())).count(),
                (int) states.stream().filter(state -> PAUSED_OR_DISABLED.contains(state.state())).count(),
                failing);
    }

    /**
     * The last {@link #HISTORY_DAYS} days of a history, counted back from the newest day it has, oldest first. A
     * day the upstream sent without a date is left out, as on the governance site.
     */
    static List<GovernanceDailyScore> lastDays(List<GovernanceDailyScore> history) {
        List<GovernanceDailyScore> dated = history.stream()
                .filter(day -> day.day() != null)
                .sorted(Comparator.comparing(GovernanceDailyScore::day))
                .toList();
        if (dated.isEmpty()) {
            return List.of();
        }
        LocalDate first = dated.getLast().day().minusDays(HISTORY_DAYS - 1L);
        return dated.stream().filter(day -> !day.day().isBefore(first)).toList();
    }

    /** In the offset of this service's time zone, as every time the generated pages print. */
    private OffsetDateTime inOwnZone(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(clock.getZone()).toOffsetDateTime();
    }

    private Links links(Site site, GovernanceSystemsIndex index, GovernanceSystem system) {
        Map<String, String> systemSlugs = GovernanceSlugs.ofLinks("system",
                index.systems().stream().map(GovernanceSystemSummary::name).toList());
        String systemSlug = systemSlugs.get(system.name());
        Map<String, String> componentSlugs = systemSlug == null ? Map.of() : GovernanceSlugs.ofLinks("component",
                system.components().stream().map(GovernanceComponent::name).toList());

        Optional<GovernanceRulesIndex> rulesIndex = resources.find(GovernanceResourceKind.RULES_INDEX, null)
                .flatMap(resource -> content.rulesIndex(resource.content()));
        List<GovernanceRuleSummary> rules = rulesIndex.map(GovernanceRulesIndex::rules).orElse(List.of());
        // A rule's page is written where its own resource is stored; one the index lists without it has none.
        Set<String> storedRules = resources.findRefs(GovernanceResourceKind.RULE).stream()
                .map(GovernanceResourceRef::key).collect(Collectors.toSet());
        Map<String, String> ruleSlugs = GovernanceSlugs.ofLinks("rule",
                rules.stream().map(GovernanceRuleSummary::ruleId).toList());
        ruleSlugs.keySet().retainAll(storedRules);
        Map<String, String> ruleNames = rules.stream()
                .filter(rule -> rule.ruleId() != null && rule.name() != null)
                .collect(Collectors.toMap(GovernanceRuleSummary::ruleId, GovernanceRuleSummary::name,
                        (first, second) -> first));

        String base = site.routePrefix();
        return new Links(
                systemSlug == null ? null : base + GovernancePaths.system(systemSlug),
                rules.isEmpty() ? null : base + GovernancePaths.rules(),
                name -> {
                    String slug = componentSlugs.get(name);
                    return slug == null ? null : base + GovernancePaths.component(systemSlug, slug);
                },
                ruleId -> {
                    String slug = ruleId == null ? null : ruleSlugs.get(ruleId);
                    return slug == null ? null : base + GovernancePaths.rule(slug);
                },
                ruleNames);
    }

    private record Links(String system, String rulesOverview, Function<String, String> componentLink,
                         Function<String, String> ruleLink, Map<String, String> ruleNames) {

        String component(String name) {
            return componentLink.apply(name);
        }

        String rule(String ruleId) {
            return ruleLink.apply(ruleId);
        }

        /** The name the rules index knows the rule by, else the one the rule state carries, else its ID. */
        String labelOf(GovernanceRuleState state) {
            String indexed = state.ruleId() == null ? null : ruleNames.get(state.ruleId());
            if (indexed != null) {
                return indexed;
            }
            return state.name() != null ? state.name() : state.ruleId();
        }
    }
}
