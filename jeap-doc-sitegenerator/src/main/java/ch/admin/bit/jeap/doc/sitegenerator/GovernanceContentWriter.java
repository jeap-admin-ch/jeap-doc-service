package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.Slugs;
import ch.admin.bit.jeap.doc.domain.content.GovernanceContent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceDailyScore;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceGracePeriodComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceNonCompliantComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernancePaths;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
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
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import ch.admin.bit.jeap.doc.markdown.CategoryFile;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static ch.admin.bit.jeap.doc.markdown.FrontMatter.frontMatter;

/**
 * Writes the governance site: the system scores overview at its root, a page per scored system, a page per
 * component, the rules overview and a page per rule. Shell only - there is nothing below it a build asks for
 * in parts, so this writes the whole site every time it runs.
 * <p>
 * Everything here is generated from what the governance import stored; nothing is uploaded for this content
 * and nothing comes from the architecture model.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GovernanceContentWriter implements ContentWriter {

    private static final String TITLE = "Governance";
    private static final String SYSTEMS_LABEL = "Systems";
    private static final String RULES_LABEL = "Rules";

    /** Active rules first, the heaviest of them first; an inactive rule's weight is null, so it sorts last. */
    private static final Comparator<GovernanceRuleSummary> RULES_OVERVIEW_ORDER = Comparator
            .comparing(GovernanceRuleSummary::active, Comparator.reverseOrder())
            .thenComparing(Comparator.comparingInt((GovernanceRuleSummary summary) ->
                    summary.weight() == null ? Integer.MIN_VALUE : summary.weight()).reversed());

    /** The best score first; a system without one sorts last, and systems with the same score by name. */
    private static final Comparator<GovernanceSystemSummary> SYSTEMS_OVERVIEW_ORDER = Comparator
            .comparing(GovernanceSystemSummary::score, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(GovernanceSystemSummary::name, Comparator.nullsLast(Comparator.naturalOrder()));

    /** The same order for the components of a system. */
    private static final Comparator<GovernanceComponent> COMPONENTS_OVERVIEW_ORDER = Comparator
            .comparing(GovernanceComponent::score, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(GovernanceComponent::name, Comparator.nullsLast(Comparator.naturalOrder()));

    private final GovernanceResourceRepository resources;
    private final GovernanceResourceContent content;

    @Override
    public String contentSource() {
        return GovernanceContent.SOURCE_ID;
    }

    @Override
    public WrittenContent write(Site site, SitePart part, Path contentDirectory,
                                 Instant generatedAt) throws IOException {
        Path tree = contentDirectory.resolve(SiteSources.SINGLE_TREE);
        Files.createDirectories(tree);

        // Every page is known before any is written, so that a link only ever points to a page that exists:
        // the site build fails on a broken link, and one unreadable resource must not take the site down.
        // All of it is read from one snapshot: an import between two reads would put the old index beside
        // new system details.
        Stored stored = resources.readConsistently(this::readStored);
        Optional<GovernanceSystemsIndex> index = stored.index();
        Optional<GovernanceRulesIndex> rulesIndex = stored.rulesIndex();
        Map<String, SystemPage> systems = stored.systems();
        Map<String, RulePage> rules = stored.rules();

        writeOverview(tree, index, systems, rulesIndex.map(r -> !r.rules().isEmpty()).orElse(false), generatedAt);
        writeSystems(tree, index, systems, rules, generatedAt);
        if (rulesIndex.isPresent()) {
            writeRules(tree, rulesIndex.get(), rules, systems, generatedAt);
        }
        // Nothing here reads an architecture model, and no timestamp of this source's own is written into a page,
        // so there is no per-environment count and no provenance for the content digest to ignore.
        return new WrittenContent(Map.of(), new LinkedHashSet<>());
    }

    /**
     * A system whose page is written, by the name the systems index lists it under, with the components whose
     * pages are written below it, by name.
     */
    private Stored readStored() {
        Optional<GovernanceSystemsIndex> index = resources.find(GovernanceResourceKind.SYSTEMS_INDEX, null)
                .flatMap(resource -> content.systemsIndex(resource.content()));
        Optional<GovernanceRulesIndex> rulesIndex = resources.find(GovernanceResourceKind.RULES_INDEX, null)
                .flatMap(resource -> content.rulesIndex(resource.content()));
        return new Stored(index, rulesIndex, index.map(this::readSystems).orElse(Map.of()),
                rulesIndex.map(this::readRules).orElse(Map.of()));
    }

    private record Stored(Optional<GovernanceSystemsIndex> index, Optional<GovernanceRulesIndex> rulesIndex,
                          Map<String, SystemPage> systems, Map<String, RulePage> rules) {
    }

    private record SystemPage(String slug, GovernanceSystem system, Map<String, ComponentPage> components) {
    }

    private record ComponentPage(String slug, GovernanceComponent component) {
    }

    /** A rule whose page is written, by the rule ID the rules index lists it under. */
    private record RulePage(String slug, GovernanceRule rule) {
    }

    private Map<String, SystemPage> readSystems(GovernanceSystemsIndex index) {
        Map<String, SystemPage> pages = new LinkedHashMap<>();
        Map<String, String> slugs = new LinkedHashMap<>();
        for (GovernanceSystemSummary summary : index.systems()) {
            String slug = slugOf("system", summary.name(), slugs);
            if (slug == null) {
                continue;
            }
            Optional<GovernanceSystem> system = resources.find(GovernanceResourceKind.SYSTEM, summary.name())
                    .flatMap(resource -> content.system(resource.content()));
            if (system.isEmpty()) {
                log.warn("The system '{}' is listed in the systems index, but its own resource could not be "
                         + "read. Its page is not generated.", summary.name());
                continue;
            }
            pages.put(summary.name(), new SystemPage(slug, system.get(), readComponents(system.get())));
        }
        return pages;
    }

    private Map<String, ComponentPage> readComponents(GovernanceSystem system) {
        Map<String, ComponentPage> pages = new LinkedHashMap<>();
        Map<String, String> slugs = new LinkedHashMap<>();
        for (GovernanceComponent component : system.components()) {
            String slug = slugOf("component", component.name(), slugs);
            if (slug != null) {
                pages.put(component.name(), new ComponentPage(slug, component));
            }
        }
        return pages;
    }

    private Map<String, RulePage> readRules(GovernanceRulesIndex rulesIndex) {
        Map<String, RulePage> pages = new LinkedHashMap<>();
        Map<String, String> slugs = new LinkedHashMap<>();
        for (GovernanceRuleSummary summary : rulesIndex.rules()) {
            String slug = slugOf("rule", summary.ruleId(), slugs);
            if (slug == null) {
                continue;
            }
            Optional<GovernanceRule> rule = resources.find(GovernanceResourceKind.RULE, summary.ruleId())
                    .flatMap(resource -> content.rule(resource.content()));
            if (rule.isEmpty()) {
                log.warn("The rule '{}' is listed in the rules index, but its own resource could not be read. "
                         + "Its page is not generated.", summary.ruleId());
                continue;
            }
            pages.put(summary.ruleId(), new RulePage(slug, rule.get()));
        }
        return pages;
    }

    /** A short introduction at the site root - the one page every reader of this site starts at. */
    private void writeOverview(Path tree, Optional<GovernanceSystemsIndex> index, Map<String, SystemPage> systems,
                               boolean hasRules, Instant generatedAt) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", TITLE)
                        .put("slug", GovernancePaths.overview())
                        .put("sidebar_label", TITLE)
                        .put("sidebar_position", 0)
                        .put("doc_status", "generated")
                        .put("doc_source", "governance-service")
                        .put("doc_generated_at", generatedAt.toString())
                        .put("doc_generated_at_display", DisplayTime.of(generatedAt)))
                .heading(1, TITLE)
                .paragraph("This site shows how the systems and components of the platform conform to the "
                           + "governance rules.")
                .paragraph(asOfSentence(index));
        if (hasSystemsPage(index, systems)) {
            page.paragraph(Md.sentence("{} lists the score and trend of every system, and of its components.",
                    Md.link(GovernancePaths.systems(), SYSTEMS_LABEL)));
        }
        if (hasRules) {
            page.paragraph(Md.sentence("{} lists every rule with its conformance rate.",
                    Md.link(GovernancePaths.rules(), RULES_LABEL)));
        }
        Files.writeString(tree.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    /** The scores per system, as the landing page of the Systems category. */
    private void writeSystemsOverview(Path systemsDirectory, Optional<GovernanceSystemsIndex> index,
                                      Map<String, SystemPage> systems, Instant generatedAt) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", SYSTEMS_LABEL)
                        .put("slug", GovernancePaths.systems())
                        .put("sidebar_label", SYSTEMS_LABEL)
                        .put("sidebar_position", 0)
                        .put("doc_status", "generated")
                        .put("doc_source", "governance-service")
                        .put("doc_generated_at", generatedAt.toString())
                        .put("doc_generated_at_display", DisplayTime.of(generatedAt)))
                .heading(1, SYSTEMS_LABEL)
                .paragraph(asOfSentence(index));

        List<List<Markdown>> rows = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        List<GovernanceSystemSummary> bestFirst = index.map(GovernanceSystemsIndex::systems).orElse(List.of())
                .stream().sorted(SYSTEMS_OVERVIEW_ORDER).toList();
        for (GovernanceSystemSummary system : bestFirst) {
            if (system.name() == null || system.name().isBlank() || !listed.add(system.name())) {
                continue;
            }
            SystemPage written = systems.get(system.name());
            Markdown name = written == null ? Md.text(system.name())
                    : Md.link(GovernancePaths.system(written.slug()), system.name());
            rows.add(List.of(name, scoreCell(system.score()), trendCell(system.trend())));
        }
        if (rows.isEmpty()) {
            page.paragraph("No system has been scored yet.");
        } else {
            page.table(List.of("System", "Score", "Trend"), rows);
        }
        Files.writeString(systemsDirectory.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    private static boolean hasSystemsPage(Optional<GovernanceSystemsIndex> index, Map<String, SystemPage> pages) {
        return !pages.isEmpty() || index.map(i -> !i.systems().isEmpty()).orElse(false);
    }

    private static Markdown asOfSentence(Optional<GovernanceSystemsIndex> index) {
        return index.map(GovernanceSystemsIndex::scoredAt)
                .map(scoredAt -> Md.sentence("The scores on this site are as of the scoring run that completed "
                                             + "at {}.", Md.bold(DisplayTime.of(scoredAt))))
                .orElse(Md.text("No scoring run has completed yet."));
    }

    /** One page per scored system, and below it one page per component the governance import stored. */
    private void writeSystems(Path tree, Optional<GovernanceSystemsIndex> index, Map<String, SystemPage> systemPages,
                              Map<String, RulePage> rules, Instant generatedAt) throws IOException {
        if (!hasSystemsPage(index, systemPages)) {
            return;
        }
        Path systems = tree.resolve(GovernancePaths.SYSTEMS_SEGMENT);
        Files.createDirectories(systems);
        Files.writeString(systems.resolve(CategoryFile.NAME), CategoryFile.of(SYSTEMS_LABEL, 1),
                StandardCharsets.UTF_8);
        writeSystemsOverview(systems, index, systemPages, generatedAt);

        for (SystemPage page : systemPages.values()) {
            writeSystem(systems.resolve(page.slug()), page, rules, generatedAt);
        }
    }

    private void writeSystem(Path directory, SystemPage written, Map<String, RulePage> rules, Instant generatedAt)
            throws IOException {
        String slug = written.slug();
        GovernanceSystem system = written.system();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(CategoryFile.NAME), CategoryFile.of(system.name()),
                StandardCharsets.UTF_8);

        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", system.name())
                        .put("slug", GovernancePaths.system(slug))
                        .put("sidebar_label", system.name())
                        .put("sidebar_position", 0)
                        .put("doc_status", "generated")
                        .put("doc_source", "governance-service")
                        .put("doc_generated_at", generatedAt.toString())
                        .put("doc_generated_at_display", DisplayTime.of(generatedAt)))
                .heading(1, system.name())
                .heading(2, "Overall")
                .table(List.of("Score", "Trend"),
                        List.of(List.of(scoreCell(system.score()), trendCell(system.trend()))));

        if (!system.components().isEmpty()) {
            page.heading(2, "Component score");
            List<List<Markdown>> rows = new ArrayList<>();
            List<ComponentPage> bestFirst = written.components().values().stream()
                    .sorted(Comparator.comparing(ComponentPage::component, COMPONENTS_OVERVIEW_ORDER)).toList();
            for (ComponentPage component : bestFirst) {
                rows.add(List.of(
                        Md.link(GovernancePaths.component(slug, component.slug()), component.component().name()),
                        scoreCell(component.component().score()), trendCell(component.component().trend())));
            }
            page.table(List.of("Component", "Score", "Trend"), rows);
        }
        writeHistory(page, system.history());
        Files.writeString(directory.resolve("index.md"), page.text(), StandardCharsets.UTF_8);

        writeComponents(directory, written, rules, generatedAt);
    }

    private void writeComponents(Path systemDirectory, SystemPage system, Map<String, RulePage> rules,
                                 Instant generatedAt) throws IOException {
        for (ComponentPage component : system.components().values()) {
            writeComponent(systemDirectory.resolve(component.slug()), system.slug(), component.slug(),
                    component.component(), rules, generatedAt);
        }
    }

    private void writeComponent(Path directory, String systemSlug, String componentSlug,
                                GovernanceComponent component, Map<String, RulePage> rules, Instant generatedAt)
            throws IOException {
        Files.createDirectories(directory);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", component.name())
                        .put("slug", GovernancePaths.component(systemSlug, componentSlug))
                        .put("sidebar_label", component.name())
                        .put("doc_status", "generated")
                        .put("doc_source", "governance-service")
                        .put("doc_generated_at", generatedAt.toString())
                        .put("doc_generated_at_display", DisplayTime.of(generatedAt)))
                .heading(1, component.name())
                .table(List.of("Score", "Trend"),
                        List.of(List.of(scoreCell(component.score()), trendCell(component.trend()))));

        writeHistory(page, component.history());

        if (!component.ruleStates().isEmpty()) {
            page.heading(2, "Rules");
            List<List<Markdown>> rows = new ArrayList<>();
            for (GovernanceRuleState state : component.ruleStates()) {
                Markdown comment = withDetails(state.comment(), state.details());
                RulePage rule = state.ruleId() == null ? null : rules.get(state.ruleId());
                Markdown name = Md.textOr(state.name(), state.ruleId());
                rows.add(List.of(rule == null ? name : Md.link(GovernancePaths.rule(rule.slug()), name),
                        stateCell(state.state()), comment));
            }
            page.table(List.of("Rule", "State", "Comment"), rows);
        }
        Files.writeString(directory.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    /** The first line of a result, and its further lines as a bullet list under it - all in the one cell. */
    private static Markdown withDetails(String comment, List<String> details) {
        Markdown first = comment == null ? Markdown.EMPTY : Md.text(comment);
        Markdown list = Md.listInCell(details.stream().map(Md::text).toList());
        if (list.isEmpty()) {
            return first;
        }
        return first.isEmpty() ? list : Md.joinWith(" ", List.of(first, list));
    }

    /** The daily score history as a chart. A day the upstream sent without a date is left out. */
    private static void writeHistory(MarkdownWriter page, List<GovernanceDailyScore> recorded) {
        List<GovernanceDailyScore> history = recorded.stream().filter(day -> day.day() != null).toList();
        if (history.isEmpty()) {
            return;
        }
        page.heading(2, "Score history");
        page.fence("mermaid", historyChart(history));
    }

    /**
     * A line over the days, in the one chart syntax Mermaid offers for this: every value here is a day or a
     * whole percent the Governance Service computed, never free text, so nothing needs escaping the way a
     * cell on the page does. {@code MermaidChartBrowserIT} draws a hand-made copy of this syntax, so change
     * that copy with it.
     */
    private static String historyChart(List<GovernanceDailyScore> history) {
        String days = history.stream()
                .map(day -> "\"" + day.day() + "\"")
                .collect(Collectors.joining(", "));
        String scores = history.stream()
                .map(day -> String.valueOf(day.score()))
                .collect(Collectors.joining(", "));
        // The light theme draws the first line in #EEE, nearly invisible on a white page; this is the dark
        // theme's own first colour, so both modes show the same line. A day stands upright, or a month of
        // them overlaps.
        return """
                ---
                config:
                  xyChart:
                    xAxis:
                      labelRotation: -90
                  themeVariables:
                    xyChart:
                      plotColorPalette: "#3498db"
                ---
                xychart-beta
                    title "Score history"
                    x-axis [%s]
                    y-axis "Score (%%)" 0 --> 100
                    line [%s]""".formatted(days, scores);
    }

    /**
     * One page per known rule, active or not, and below them the rules overview - all of it reads the same
     * per-rule resources, fetched once and reused for the overview's exemption folds.
     */
    private void writeRules(Path tree, GovernanceRulesIndex rulesIndex, Map<String, RulePage> rulePages,
                            Map<String, SystemPage> systems, Instant generatedAt) throws IOException {
        if (rulesIndex.rules().isEmpty()) {
            return;
        }
        Path rules = tree.resolve(GovernancePaths.RULES_SEGMENT);
        Files.createDirectories(rules);
        Files.writeString(rules.resolve(CategoryFile.NAME), CategoryFile.of(RULES_LABEL, 2),
                StandardCharsets.UTF_8);

        for (Map.Entry<String, RulePage> page : rulePages.entrySet()) {
            writeRule(rules.resolve(page.getValue().slug()), page.getKey(), page.getValue(), systems, generatedAt);
        }
        writeRulesOverview(rules, rulesIndex, rulePages, generatedAt);
    }

    /**
     * All known rules, active ones first by weight, and below the table a fold per rule with exemptions. A rule
     * whose page could not be written is still listed, by name and without a link.
     */
    private static void writeRulesOverview(Path rules, GovernanceRulesIndex rulesIndex,
                                           Map<String, RulePage> rulePages, Instant generatedAt)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", RULES_LABEL)
                        .put("slug", GovernancePaths.rules())
                        .put("sidebar_label", RULES_LABEL)
                        .put("sidebar_position", 0)
                        .put("doc_status", "generated")
                        .put("doc_source", "governance-service")
                        .put("doc_generated_at", generatedAt.toString())
                        .put("doc_generated_at_display", DisplayTime.of(generatedAt)))
                .heading(1, RULES_LABEL)
                .paragraph("The share shown for an active rule is nominal: where other rules are disabled for "
                           + "a component, this rule weighs more there.");

        List<GovernanceRuleSummary> ordered = rulesIndex.rules().stream()
                .filter(summary -> summary.ruleId() != null && !summary.ruleId().isBlank())
                .filter(distinctBy(GovernanceRuleSummary::ruleId))
                .sorted(RULES_OVERVIEW_ORDER)
                .toList();
        List<List<Markdown>> rows = new ArrayList<>();
        for (GovernanceRuleSummary summary : ordered) {
            RulePage written = rulePages.get(summary.ruleId());
            Markdown name = Md.textOr(summary.name(), summary.ruleId());
            rows.add(List.of(written == null ? name : Md.link(GovernancePaths.rule(written.slug()), name),
                    activeCell(summary.active()), weightCell(summary.weight()), shareCell(summary.share()),
                    scoreCell(summary.conformanceRate()), trendCell(summary.trend()),
                    countCell(summary.exemptionCount())));
        }
        page.table(List.of("Rule", "Active", "Weight", "Share", "Conformance", "Trend", "Exemptions"), rows);

        for (GovernanceRuleSummary summary : ordered) {
            RulePage written = rulePages.get(summary.ruleId());
            if (written != null && !written.rule().exemptions().isEmpty()) {
                writeExemptionsFold(page, summary.ruleId(), written.rule());
            }
        }
        Files.writeString(rules.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    private static <T> Predicate<T> distinctBy(Function<T, String> key) {
        Set<String> seen = new HashSet<>();
        return value -> seen.add(key.apply(value));
    }

    /** A collapsed fold per rule with exemptions - a rule without any shows none. */
    private static void writeExemptionsFold(MarkdownWriter page, String ruleId, GovernanceRule rule) {
        int count = rule.exemptions().size();
        String summary = nameOf(rule, ruleId) + " - " + count + (count == 1 ? " exemption" : " exemptions");
        page.details(summary, folded -> {
            List<List<Markdown>> rows = rule.exemptions().stream()
                    .map(GovernanceContentWriter::exemptionRow)
                    .toList();
            folded.table(List.of("Component", "Reason", "End day", "Effect"), rows);
        });
    }

    private static List<Markdown> exemptionRow(GovernanceRuleExemption exemption) {
        return List.of(Md.text(exemption.component()), Md.text(exemption.reason()),
                dayCell(exemption.until()), Md.text(exemptionEffectLabel(exemption.effect())));
    }

    private void writeRule(Path directory, String ruleId, RulePage written, Map<String, SystemPage> systems,
                           Instant generatedAt) throws IOException {
        GovernanceRule rule = written.rule();
        String name = nameOf(rule, ruleId);
        Files.createDirectories(directory);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", name)
                        .put("slug", GovernancePaths.rule(written.slug()))
                        .put("sidebar_label", name)
                        .put("doc_status", "generated")
                        .put("doc_source", "governance-service")
                        .put("doc_generated_at", generatedAt.toString())
                        .put("doc_generated_at_display", DisplayTime.of(generatedAt)))
                .heading(1, name)
                .paragraph(Md.join(Md.text("Rule ID: "), Md.code(ruleId)));
        writeExplanation(page, rule.explanation(), rule.active());

        if (rule.active()) {
            page.table(List.of("Weight", "Violation delay"),
                    List.of(List.of(weightCell(rule.weight()), violationDelayCell(rule.violationDelay()))));
            writeParameters(page, rule.parameters());
            writeConformance(page, rule, systems);
            writeNonCompliantComponents(page, rule.nonCompliantComponents(), systems);
            writeGracePeriod(page, rule, systems);
        }
        // An inactive rule's page carries the explanation only - nothing else above is written for one.

        Files.writeString(directory.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    /** Folded on an active rule, so the conformance facts stay near the top; an inactive rule has nothing else. */
    private static void writeExplanation(MarkdownWriter page, String explanation, boolean folded) {
        if (explanation == null || explanation.isBlank()) {
            page.paragraph("No explanation is available for this rule.");
        } else {
            if (folded) {
                page.details("Explanation", body -> body.importedDocument(explanation));
            } else {
                page.importedDocument(explanation);
            }
        }
    }

    private static void writeParameters(MarkdownWriter page, List<GovernanceRuleParameter> parameters) {
        if (parameters.isEmpty()) {
            return;
        }
        page.heading(2, "Parameters");
        List<List<Markdown>> rows = parameters.stream()
                .map(parameter -> List.of(Md.code(parameter.name()), Md.text(parameter.value())))
                .toList();
        page.table(List.of("Parameter", "Value"), rows);
    }

    private static void writeConformance(MarkdownWriter page, GovernanceRule rule, Map<String, SystemPage> systems) {
        page.heading(2, "Conformance");
        if (rule.conformanceRate() == null) {
            page.paragraph("No conformance rate is available yet.");
        } else {
            page.paragraph(Md.sentence("The overall conformance rate is {}.",
                    scoreCell(rule.conformanceRate())));
        }
        if (!rule.systems().isEmpty()) {
            page.heading(3, "By system");
            List<List<Markdown>> rows = rule.systems().stream()
                    .sorted(Comparator.comparing(GovernanceSystemConformance::rate,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(system -> List.of(systemCell(system.system(), systems), scoreCell(system.rate())))
                    .toList();
            page.table(List.of("System", "Rate"), rows);
        }
    }

    private static void writeNonCompliantComponents(MarkdownWriter page,
                                                     List<GovernanceNonCompliantComponent> components,
                                                     Map<String, SystemPage> systems) {
        if (components.isEmpty()) {
            return;
        }
        page.heading(2, "Non-compliant components");
        List<List<Markdown>> rows = components.stream()
                .map(component -> List.of(systemCell(component.system(), systems),
                        componentCell(component.system(), component.component(), systems),
                        dayCell(component.since())))
                .toList();
        page.table(List.of("System", "Component", "Non-compliant since"), rows);
    }

    /** Written only when this rule has a grace period at all - a rule without one shows no such section. */
    private static void writeGracePeriod(MarkdownWriter page, GovernanceRule rule, Map<String, SystemPage> systems) {
        if (!rule.gracePeriodConfigured()) {
            return;
        }
        page.heading(2, "Violation grace period");
        if (rule.gracePeriodComponents().isEmpty()) {
            page.paragraph("No component is currently within the grace period.");
            return;
        }
        List<List<Markdown>> rows = rule.gracePeriodComponents().stream()
                .map(component -> gracePeriodRow(component, systems))
                .toList();
        page.table(List.of("System", "Component", "Comment", "Detected", "Grace period ends"), rows);
    }

    private static List<Markdown> gracePeriodRow(GovernanceGracePeriodComponent component,
                                                 Map<String, SystemPage> systems) {
        return List.of(systemCell(component.system(), systems),
                componentCell(component.system(), component.component(), systems),
                withDetails(component.comment(), component.details()), timeCell(component.detectedAt()),
                timeCell(component.gracePeriodEndsAt()));
    }

    /** A link to the system's page where it has one, and its name otherwise. */
    private static Markdown systemCell(String name, Map<String, SystemPage> systems) {
        SystemPage system = name == null ? null : systems.get(name);
        return system == null ? Md.text(name) : Md.link(GovernancePaths.system(system.slug()), name);
    }

    /** A link to the component's page where it has one, and its name otherwise. */
    private static Markdown componentCell(String systemName, String name, Map<String, SystemPage> systems) {
        SystemPage system = systemName == null ? null : systems.get(systemName);
        ComponentPage component = system == null || name == null ? null : system.components().get(name);
        return component == null ? Md.text(name)
                : Md.link(GovernancePaths.component(system.slug(), component.slug()), name);
    }

    private static Markdown timeCell(OffsetDateTime when) {
        return when == null ? Markdown.EMPTY : Md.text(DisplayTime.of(when));
    }

    /** The rule's own name, or its rule ID where the resource carries none. */
    private static String nameOf(GovernanceRule rule, String ruleId) {
        return rule.name() == null || rule.name().isBlank() ? ruleId : rule.name();
    }

    private static Markdown weightCell(Integer weight) {
        return weight == null ? Markdown.EMPTY : Md.text(String.valueOf(weight));
    }

    /** A friendly rendering of a configured delay - whole days where it divides evenly, hours otherwise. */
    private static Markdown violationDelayCell(Duration delay) {
        if (delay == null) {
            return Markdown.EMPTY;
        }
        long hours = delay.toHours();
        if (hours > 0 && hours % 24 == 0) {
            long days = hours / 24;
            return Md.text(days + (days == 1 ? " day" : " days"));
        }
        return Md.text(hours + (hours == 1 ? " hour" : " hours"));
    }

    /**
     * The slug of a name or rule ID, refusing a second one that would overwrite the first page - see {@code
     * ArchitectureModelImportStep.slugOf} for the same rule. Logged and left without a page rather than failing
     * the whole site: a name collision in the Governance Service's own data must not take every other page down
     * with it. A value that yields no slug at all - {@code ..} among them - gets no page either, so nothing
     * from upstream is ever used as a path segment unslugged.
     */
    private String slugOf(String what, String name, Map<String, String> taken) {
        String slug;
        try {
            slug = Slugs.toSlug(name);
        } catch (IllegalArgumentException e) {
            log.warn("The {} '{}' has no slug, so no page is generated for it.", what, name);
            return null;
        }
        String already = taken.putIfAbsent(slug, name);
        if (already == null) {
            return slug;
        }
        if (already.equals(name)) {
            return null; // the same name twice in one list - write it once, not once per occurrence
        }
        log.warn("The {}s '{}' and '{}' would both be documented at '{}'. No page is generated for '{}'.",
                what, already, name, slug, name);
        return null;
    }

    /** A coloured marker before the value: green from 80 percent, yellow from 50, red below. */
    private static Markdown scoreCell(Integer score) {
        if (score == null) {
            return Markdown.EMPTY;
        }
        return Md.text(scoreMarker(score) + " " + score + "%");
    }

    private static String scoreMarker(int score) {
        if (score >= 80) {
            return "🟢";
        }
        if (score >= 50) {
            return "🟡";
        }
        return "🔴";
    }

    /** Always one or the other - a rule's resource always says whether it is active. */
    private static Markdown activeCell(boolean active) {
        return Md.text(active ? "Active" : "Inactive");
    }

    /** Rounded to one decimal, as the Governance Service computes it - never a whole number by coincidence. */
    private static Markdown shareCell(BigDecimal share) {
        return share == null ? Markdown.EMPTY : Md.text(share.toPlainString() + "%");
    }

    private static Markdown countCell(Integer count) {
        return count == null ? Markdown.EMPTY : Md.text(String.valueOf(count));
    }

    private static Markdown dayCell(LocalDate day) {
        return day == null ? Markdown.EMPTY : Md.text(day.toString());
    }

    private static String exemptionEffectLabel(String effect) {
        if (effect == null) {
            return "Unknown";
        }
        return switch (effect) {
            case "PAUSED" -> "Paused";
            case "DISABLED" -> "Disabled";
            case "EXPIRED" -> "Expired";
            default -> "Unknown";
        };
    }

    /** An arrow before a word a reader understands without a legend. No arrow where there is no trend yet. */
    private static Markdown trendCell(String trend) {
        if (trend == null) {
            return Markdown.EMPTY;
        }
        return switch (trend) {
            case "UP" -> Md.text("↑ Up");
            case "DOWN" -> Md.text("↓ Down");
            case "STABLE" -> Md.text("→ Stable");
            case "NO_DATA" -> Markdown.EMPTY;
            default -> Md.text(trend.toLowerCase(Locale.ROOT));
        };
    }

    /** A marker before the label, one of each for compliant, non-compliant, paused, disabled and unknown. */
    private static Markdown stateCell(String state) {
        if (state == null) {
            return Md.text("❔ Unknown");
        }
        return switch (state) {
            case "OK" -> Md.text("✅ Compliant");
            case "FAIL" -> Md.text("❌ Non-compliant");
            case "PAUSED" -> Md.text("⏸️ Paused");
            case "DISABLED" -> Md.text("⛔ Disabled");
            default -> Md.text("❔ Unknown");
        };
    }
}
