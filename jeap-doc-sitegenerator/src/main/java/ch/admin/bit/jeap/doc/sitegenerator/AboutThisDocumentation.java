package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.DocumentationFacts;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ch.admin.bit.jeap.doc.markdown.FrontMatter.frontMatter;

/**
 * The page that documents the documentation: what a reader is looking at, where it comes from, and when it
 * changes next.
 * <p>
 * <b>One page per environment tree</b>, beside the root page. A single site-level page is not possible: the
 * site template switches Docusaurus' pages plugin off, and writing the page into the main tree alone would fail
 * the build, because the environment links plugin prefixes a root-relative link with the tree it is read in -
 * so a link from DEV to {@code /about-this-documentation/} would resolve to a page nothing wrote, and the site
 * is generated with {@code onBrokenLinks: 'throw'}.
 * <p>
 * <b>What a build costs is not written here.</b> A page cannot describe the build that writes it: the pages,
 * the bytes, the duration and the memory peak are known at the end of a run, and this is written at the
 * beginning of one. Printing the numbers of the publication before it would print numbers that are not the
 * reader's. So the page names its own build and the moment it was written - both exact - and the five metrics
 * are written beside it as JSON once they are known, and fetched by the site template. Everything else on the
 * page is exact as written.
 */
@Component
public class AboutThisDocumentation {

    /**
     * The file, beside the root page of each environment tree.
     * <p>
     * The route Docusaurus derives from it - {@code /about-this-documentation/} - is written out by hand in
     * the two places that link to it, {@code root-page.md} and the template's {@code docusaurus.config.js},
     * because neither is Java. Renaming this file means renaming it there, and the site is built with
     * {@code onBrokenLinks: 'throw'}, so a rename that misses one fails the build rather than the page.
     */
    static final String FILE_NAME = "about-this-documentation.md";

    /** What the page is called, in its front matter, its heading and every link to it. */
    static final String TITLE = "About This Documentation";

    /**
     * The heading the site template's client module fills the metrics in after. It is the anchor Docusaurus
     * derives from the text, so the two have to agree - which is why the heading is a constant here and
     * {@code HEADING_ID} in {@code publicationNumbers.js}. Nothing checks that agreement at compile time;
     * what catches it is the browser test asserting the fetched numbers appear on the page.
     */
    static final String PUBLICATION_HEADING = "The publication you are reading";

    /** Where the numbers of this build are written, relative to the root of the served site. */
    static final String STATUS_FILE = "about-this-documentation.json";

    /**
     * After the root page (0) and the systems tree (1). A later top-level folder takes the next number, so
     * nothing has to be renumbered to add one.
     */
    private static final int SIDEBAR_POSITION = 2;

    /**
     * Writes the page into one environment tree.
     *
     * @param facts                the publishable facts of the site - see {@code DocumentationProvenance}
     * @param environment          the environment whose tree this is
     * @param models               what each environment's architecture model contributed, by environment id
     * @param buildId              the build writing this page, which its own log lines name
     * @param statusUrl            the absolute URL the numbers of this build are published at
     * @param environmentDirectory {@code content/<environment>}
     */
    public void write(DocumentationFacts facts, SiteEnvironment environment,
                      Map<String, EnvironmentModel> models, long buildId, String statusUrl,
                      Path environmentDirectory) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", TITLE)
                        .put("sidebar_label", TITLE)
                        .put("sidebar_position", SIDEBAR_POSITION)
                        .put("doc_status", "generated")
                        // A value of its own beside 'archrepo': this page is written from the service's own
                        // configuration and its own records, and calling it archrepo would make doc_source
                        // useless as a filter.
                        .put("doc_source", "doc-service")
                        .put("doc_environment", environment.id())
                        .put("doc_generated_at", facts.service().generatedAt().toString()))
                .heading(1, TITLE)
                .paragraph(Md.sentence("What you are reading is generated by the jEAP Doc Service{}. This page "
                                       + "says where it comes from, when it changes, and what the run that "
                                       + "produced it did.",
                        Md.text(facts.service().version() == null ? ""
                                : " " + facts.service().version())));

        writeWhatThisIs(page, facts, environment);
        writePublication(page, facts, buildId, statusUrl);
        writeEnvironments(page, facts, models);
        writeSchedules(page, facts);
        page.admonition("info", "Generated page", Md.sentence(
                "Generated by the jEAP Doc Service from its own configuration and records on {}.",
                Md.text(DisplayTime.of(facts.service().generatedAt()))));

        Files.createDirectories(environmentDirectory);
        Files.writeString(environmentDirectory.resolve(FILE_NAME), page.text(), StandardCharsets.UTF_8);
    }

    /** The site, the tree, and the documentation structures this instance generates. */
    private void writeWhatThisIs(MarkdownWriter page, DocumentationFacts facts, SiteEnvironment environment) {
        page.heading(2, "What this is");
        List<List<Markdown>> rows = new ArrayList<>();
        rows.add(List.of(Md.text("Site"), Md.join(Md.text(facts.site().title()), Md.text(" ("),
                Md.code(facts.site().id()), Md.text(")"))));
        rows.add(List.of(Md.text("Environment"), Md.join(Md.text(environment.label()), Md.text(" ("),
                Md.code(environment.id()), Md.text(")"))));
        rows.add(List.of(Md.text("Documentation structures"), structures(facts)));
        rows.add(List.of(Md.text("Published sites kept"),
                Md.text(String.valueOf(facts.site().retainedPublications()))));
        rows.add(List.of(Md.text("An upload publishes the site"), yesOrNo(facts.site().publishOnUpload())));
        rows.add(List.of(Md.text("Waits for the architecture model"),
                yesOrNo(facts.site().architectureModelRequired())));
        page.table(List.of("", ""), rows);
    }

    /**
     * The build the reader is being served, and where its numbers come from.
     * <p>
     * The identifier and the moment are written here because they are known here. The rest arrives from
     * {@link #STATUS_FILE}, which the run writes once it has them - so the sentence has to read correctly on
     * its own, for a reader whose browser runs no scripts.
     */
    private void writePublication(MarkdownWriter page, DocumentationFacts facts, long buildId,
                                  String statusUrl) {
        page.heading(2, PUBLICATION_HEADING);
        page.paragraph(Md.sentence("This site was generated by build {} on {}. What that run cost - its pages, "
                                   + "its size, how long it took and how much memory it needed - is published "
                                   + "beside this page as {}, because a page cannot describe the build that "
                                   + "writes it.",
                Md.code(String.valueOf(buildId)),
                Md.text(DisplayTime.of(facts.service().generatedAt())),
                // The absolute URL, and deliberately so: the file is not a page of the site, so a
                // root-relative link to it would be prefixed with the environment by the links plugin and then
                // reported as broken by a link checker that only knows routes.
                Md.link(statusUrl, STATUS_FILE)));
    }

    /**
     * One row per environment: what its tree contains and where that came from.
     * <p>
     * The counts come from the run rather than from a query - see {@link EnvironmentModel} - and an environment
     * that reads no architecture model says so, rather than showing zeros that would read as an empty
     * landscape.
     */
    private void writeEnvironments(MarkdownWriter page, DocumentationFacts facts,
                                   Map<String, EnvironmentModel> models) {
        page.heading(2, "The environments of this site");
        page.paragraph("Every environment is a tree of the same documentation showing the state of one stage. "
                       + "The one marked as the main tree is served at the root of the site; the others carry "
                       + "a banner and are not offered to search engines.");
        List<List<Markdown>> rows = new ArrayList<>();
        for (DocumentationFacts.EnvironmentFacts environment : facts.environments()) {
            EnvironmentModel model = models.get(environment.id());
            rows.add(List.of(
                    Md.code(environment.id()),
                    Md.text(environment.label()),
                    Md.text(treeOf(environment)),
                    model == null ? Md.italic("no architecture model") : counted(model),
                    importedAt(environment, model, facts.service().generatedAt())));
        }
        page.table(List.of("Environment", "Name", "Tree", "From the architecture model", "Model imported"),
                rows);
    }

    private static String treeOf(DocumentationFacts.EnvironmentFacts environment) {
        if (environment.main() && environment.latest()) {
            return "main, latest";
        }
        if (environment.main()) {
            return "main";
        }
        return environment.latest() ? "latest" : "";
    }

    private static Markdown counted(EnvironmentModel model) {
        return Md.text("%d systems, %d components, %d messages"
                .formatted(model.systems(), model.components(), model.messages()));
    }

    /**
     * When the content of this tree's model was imported, when the architecture repository was last read, and
     * whether the import is behind.
     * <p>
     * Three different things, and the page keeps them apart because they answer differently: a landscape
     * nobody has changed for a month is not stale, an import that stopped running is, and a run that failed
     * says nothing at all about the content that is being served.
     * <p>
     * <b>An empty landscape is not an unimported one.</b> A stage whose architecture repository reports no
     * system at all is imported successfully, hour after hour, and stores no row - so there is no content
     * timestamp to print, and printing <i>never</i> would call a working import a missing one. The last read
     * is what the page then shows.
     */
    private static Markdown importedAt(DocumentationFacts.EnvironmentFacts environment, EnvironmentModel model,
                                       Instant now) {
        if (!environment.modelConfigured()) {
            return Md.text("");
        }
        Instant content = model == null ? null : model.importedAt();
        if (content == null && environment.lastImportAt() == null) {
            return Md.italic("never");
        }
        String imported = DisplayTime.orEmpty(content);
        return Md.text(imported + lastReadOf(environment, imported.isEmpty(), now));
    }

    /**
     * When the repository was last read successfully, and what has happened since.
     * <p>
     * The outcome belongs to the <b>latest</b> run and the timestamp to the last <b>successful</b> one, so the
     * two may not be joined into one phrase: a repository that has been down since eleven would otherwise read
     * "last read 10:00 (failed)", which says the read that worked did not.
     */
    private static String lastReadOf(DocumentationFacts.EnvironmentFacts environment, boolean first,
                                     Instant now) {
        if (environment.lastImportAt() == null) {
            return first ? "not read successfully yet" : "";
        }
        String read = (first ? "last read " : ", last read ") + DisplayTime.of(environment.lastImportAt());
        if (succeeded(environment.lastImportOutcome())) {
            return read;
        }
        // Named apart from the timestamp, and only where it matters: a run that neither replaced nor confirmed
        // the landscape is what an operator has to act on, and the staleness measure is the build's own.
        return read + (environment.importIsBehind(now) ? "; not read since" : "; the last run did not read it");
    }

    /** Whether an outcome is one that read the repository through - the two the staleness measure counts. */
    private static boolean succeeded(ImportOutcome outcome) {
        return outcome == null || outcome == ImportOutcome.REPLACED || outcome == ImportOutcome.UNCHANGED;
    }

    /**
     * What is generated without a structure template depends on whether an architecture model is read at all:
     * with one, the system index and the landing pages still are; without one, nothing is, and the tree carries
     * the root page and whatever was uploaded into it.
     */
    private static Markdown structures(DocumentationFacts facts) {
        if (!facts.site().templates().isEmpty()) {
            return Md.joinWith(", ", facts.site().templates().stream().map(Md::text).toList());
        }
        if (readsAModel(facts)) {
            return Md.italic("none - only the system index and the landing pages are generated");
        }
        return Md.italic("none");
    }

    /** Whether any environment of this site reads an architecture model at all. */
    private static boolean readsAModel(DocumentationFacts facts) {
        return facts.environments().stream().anyMatch(DocumentationFacts.EnvironmentFacts::modelConfigured);
    }

    /** When the documentation changes next, and what it is that changes it. */
    private void writeSchedules(MarkdownWriter page, DocumentationFacts facts) {
        page.heading(2, "When this changes");
        List<List<Markdown>> rows = new ArrayList<>();
        rows.add(scheduleRow("This site is published", facts.schedules().publication(),
                facts.schedules().publicationAt(), facts.service().generatedAt(),
                facts.site().publishOnUpload() ? "only when something is uploaded to it"
                        : "only when an operator asks for it"));
        if (facts.schedules().import_() != null || facts.environments().stream()
                .anyMatch(DocumentationFacts.EnvironmentFacts::modelConfigured)) {
            rows.add(scheduleRow("The architecture model is imported", facts.schedules().import_(),
                    facts.schedules().importAt(), facts.service().generatedAt(), "not on a schedule"));
        }
        page.table(List.of("", "Schedule", "Next"), rows);
        // Only where it is true. The table three headings above prints "An upload publishes the site: no"
        // wherever it is not, and a page that says both is a page a reader cannot use.
        if (facts.site().publishOnUpload()) {
            page.paragraph("An upload publishes the site it belongs to as soon as it arrives, so the schedule "
                           + "is the slowest the documentation changes rather than the only time it does.");
        }
    }

    private static List<Markdown> scheduleRow(String what, String cron, Instant next, Instant now,
                                              String whenThereIsNone) {
        if (cron == null || cron.isBlank()) {
            return List.of(Md.text(what), Md.italic(whenThereIsNone), Md.text(""));
        }
        return List.of(Md.text(what), Md.code(cron), next == null ? Md.text("")
                : Md.text(DisplayTime.of(next) + " (" + spellOut(Duration.between(now, next)) + ")"));
    }

    /** A duration as a reader says it. Minutes and hours only: nothing here is worth a second. */
    static String spellOut(Duration duration) {
        long minutes = Math.max(duration.toMinutes(), 0);
        if (minutes < 1) {
            return "in a moment";
        }
        if (minutes < 60) {
            return "in %d minute%s".formatted(minutes, minutes == 1 ? "" : "s");
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        String spelled = "in %d hour%s".formatted(hours, hours == 1 ? "" : "s");
        if (rest == 0) {
            return spelled;
        }
        return spelled + " %d minute%s".formatted(rest, rest == 1 ? "" : "s");
    }

    private static Markdown yesOrNo(boolean value) {
        return Md.text(value ? "yes" : "no");
    }
}
