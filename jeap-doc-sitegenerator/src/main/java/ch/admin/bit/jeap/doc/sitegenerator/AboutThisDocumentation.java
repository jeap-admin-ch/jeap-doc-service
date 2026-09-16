package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.DocumentationFacts;
import ch.admin.bit.jeap.doc.domain.DocumentationLiveStatus;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <b>What a build costs is not written here.</b> A page cannot describe the build that writes it: how long a
 * run took is known at the end of it, and this is written at the beginning. Printing the duration of the
 * publication before it would print a number that is not the reader's. So the page names its own build and the
 * moment it was written - both exact - and the duration is written beside it as JSON once it is known, and
 * fetched by the site template.
 * <p>
 * <b>And what is true only now is not written here either.</b> This page is generated content, so a part whose
 * documentation has not moved is not built again and the page stays as it was - which is truthful for
 * provenance and a lie for status. So the page carries when a model's content was imported, which build wrote
 * it and when; it does not carry when the architecture repository was last read, whether the import is behind,
 * or when the schedule fires next. Those are served live as {@link DocumentationLiveStatus#FILE_NAME} and
 * filled into the cells left for them - and the page says where they are, so that it reads correctly for a
 * reader whose browser runs no scripts.
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
     * The column headings whose cells the site template's client module fills from the live status. They are
     * how it finds the table and the right cell in it - nothing else on the page carries them - so these and
     * the constants in {@code liveStatus.js} have to agree. What catches a disagreement is the browser test
     * asserting the fetched values appear on the page.
     */
    static final String SCHEDULE_COLUMN = "Schedule";

    static final String NEXT_COLUMN = "Next";

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
     * @param liveStatusUrl        the absolute URL the service answers this site's live status at
     * @param environmentDirectory {@code content/<environment>}
     */
    public void write(DocumentationFacts facts, SiteEnvironment environment,
                      Map<String, EnvironmentModel> models, long buildId, String statusUrl,
                      String liveStatusUrl, Path environmentDirectory) throws IOException {
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
                        .put("doc_generated_at", facts.service().generatedAt().toString())
                        .put("doc_generated_at_display", DisplayTime.of(facts.service().generatedAt())))
                .heading(1, TITLE)
                .paragraph(Md.sentence("What you are reading is generated by the jEAP Doc Service{}.",
                        // The space goes into the pattern: Md.text strips a leading one.
                        facts.service().version() == null ? Markdown.EMPTY
                                : Md.sentence(" {}", Md.text(facts.service().version()))));

        writeOverview(page, facts, environment);
        writePublication(page, facts, buildId, statusUrl);
        writeEnvironments(page, facts, models);
        writeSchedules(page, facts, liveStatusUrl);

        Files.createDirectories(environmentDirectory);
        Files.writeString(environmentDirectory.resolve(FILE_NAME), page.text(), StandardCharsets.UTF_8);
    }

    /** The site, the tree, and the structure templates this instance generates. */
    private void writeOverview(MarkdownWriter page, DocumentationFacts facts, SiteEnvironment environment) {
        page.heading(2, "Overview");
        List<List<Markdown>> rows = new ArrayList<>();
        // Md.sentence and not Md.join: Md.text drops a leading space, so a separator written as Md.text(" (")
        // would render the name and the id run together.
        rows.add(List.of(Md.text("Site"), Md.sentence("{} ({})",
                Md.text(facts.site().title()), Md.code(facts.site().id()))));
        rows.add(List.of(Md.text("Environment"), Md.sentence("{} ({})",
                Md.text(environment.label()), Md.code(environment.id()))));
        rows.add(List.of(Md.text("Available Documentation Structure Templates"), structures(facts)));
        rows.add(List.of(Md.text("Number of published site versions kept"),
                Md.text(String.valueOf(facts.site().retainedPublications()))));
        rows.add(List.of(Md.text("An upload publishes the site"), yesOrNo(facts.site().publishOnUpload())));
        rows.add(List.of(Md.text("Waits for the architecture model"),
                yesOrNo(facts.site().architectureModelRequired())));
        page.table(List.of("", ""), rows);
    }

    /**
     * The build the reader is being served, and where its numbers come from.
     * <p>
     * The identifier and the moment are written here because they are known here. How long the run took arrives
     * from {@link #STATUS_FILE}, which the run writes once it knows it - so the sentence has to read correctly
     * on its own, for a reader whose browser runs no scripts.
     * <p>
     * <b>How much a run produced is not among them.</b> A site is generated one part at a time, and only the
     * part carrying this page publishes its {@link #STATUS_FILE} where the page can fetch it - so a page count
     * and a size would be that one part's while reading as the whole site's. What is left is what a reader can
     * take at face value: this page belongs to that build, and that build took that long.
     */
    private void writePublication(MarkdownWriter page, DocumentationFacts facts, long buildId,
                                  String statusUrl) {
        page.heading(2, PUBLICATION_HEADING);
        page.paragraph(Md.sentence("This site was generated by build {} on {}.",
                Md.code(String.valueOf(buildId)),
                Md.text(DisplayTime.of(facts.service().generatedAt()))));
        // The absolute URL, and deliberately so: the file is not a page of the site, so a root-relative link
        // to it would be prefixed with the environment by the links plugin and then reported as broken by a
        // link checker that only knows routes.
        page.paragraph(Md.sentence("Source: {}", Md.link(statusUrl, STATUS_FILE)));
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
                       + "The one marked as the main tree is served at the root of the site.");
        List<List<Markdown>> rows = new ArrayList<>();
        for (DocumentationFacts.EnvironmentFacts environment : facts.environments()) {
            EnvironmentModel model = models.get(environment.id());
            rows.add(List.of(
                    Md.code(environment.id()),
                    Md.text(environment.label()),
                    Md.text(treeOf(environment)),
                    model == null ? Md.italic("no architecture model") : counted(model),
                    importedAt(environment, model)));
        }
        page.table(List.of("Environment", "Name", "Tree", "From the architecture model", "Model imported"),
                rows);
    }

    /**
     * Where the cells this page leaves empty are filled from.
     * <p>
     * <b>The absolute URL, and the only place it appears.</b> The client module takes the path out of this
     * link, so the link a reader can follow and the request the module makes cannot disagree.
     */
    private void writeLiveStatusNote(MarkdownWriter page, String liveStatusUrl) {
        page.paragraph(Md.sentence("Source: {}",
                Md.link(liveStatusUrl, DocumentationLiveStatus.FILE_NAME)));
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

    /**
     * What the column says it is: what came out of the architecture model. Not what the tree documents - a
     * system documented before anything is deployed is in the tree and in no landscape, and counting it here
     * would say the architecture repository holds it.
     */
    private static Markdown counted(EnvironmentModel model) {
        return Md.text("%d systems, %d components, %d messages"
                .formatted(model.modelSystems(), model.components(), model.messages()));
    }

    /**
     * When the content of this tree's model was imported - and nothing else.
     * <p>
     * <b>Provenance, which is why it may be frozen.</b> A tree that is not generated again keeps this
     * timestamp, and that is the truthful reading: <i>this content is as of then</i>. When the repository was
     * last read is a different question and one that goes stale, so it is not here - see
     * {@link DocumentationLiveStatus}.
     * <p>
     * <b>An empty landscape has no content timestamp</b>, because a stage whose architecture repository
     * reports no system at all stores no row while being imported successfully hour after hour. The cell is
     * left for the live status to speak for rather than saying <i>never</i>, which would call a working import
     * a missing one.
     */
    private static Markdown importedAt(DocumentationFacts.EnvironmentFacts environment,
                                       EnvironmentModel model) {
        if (!environment.modelConfigured()) {
            return Md.text("");
        }
        return Md.text(DisplayTime.orEmpty(model == null ? null : model.importedAt()));
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

    /** Every job of the service that runs on a schedule, and when each fires next. */
    private void writeSchedules(MarkdownWriter page, DocumentationFacts facts, String liveStatusUrl) {
        page.heading(2, "Scheduling");
        List<List<Markdown>> rows = new ArrayList<>();
        for (DocumentationFacts.Schedule schedule : facts.schedules()) {
            rows.add(scheduleRow(whatItDoes(schedule.job()), schedule.cron(),
                    whenThereIsNone(schedule.job(), facts)));
        }
        page.table(List.of("", SCHEDULE_COLUMN, NEXT_COLUMN), rows);
        writeLiveStatusNote(page, liveStatusUrl);
        // Only where it is true. The overview prints "An upload publishes the site: no" wherever it is not,
        // and a page that says both is a page a reader cannot use.
        if (facts.site().publishOnUpload()) {
            page.paragraph("An upload triggers site publication as well, a site may thus be generated more "
                           + "often than the schedule implies. Only changed parts are re-generated during a "
                           + "site build.");
        }
    }

    /** What each job does, in plain words rather than by the name of the property that schedules it. */
    private static String whatItDoes(DocumentationFacts.ScheduledJob job) {
        return switch (job) {
            // The import is what publishes the documentation: it asks for every part of every site documenting
            // the environment it read, so a site has no publication schedule of its own.
            case ARCHITECTURE_IMPORT -> "The architecture model is imported, and the site published";
            case RECONCILE -> "The sites nothing else publishes are asked for";
            case BUILD_HISTORY -> "The record of old builds is removed";
            case UPLOAD_HOUSEKEEPING -> "Uploads older than the retention are removed";
            case CUSTOM_SWEEP -> "Stored files no documentation set names are removed";
        };
    }

    /** What a row says where the job has no schedule at all. */
    private static String whenThereIsNone(DocumentationFacts.ScheduledJob job, DocumentationFacts facts) {
        if (job != DocumentationFacts.ScheduledJob.ARCHITECTURE_IMPORT) {
            return "does not run";
        }
        return facts.site().publishOnUpload() ? "only when something is uploaded to this site"
                : "only when an operator asks for it";
    }

    /**
     * One schedule. The cron expression is configuration and is printed; when it fires next moves with the
     * clock, so that cell is left for the live status.
     */
    private static List<Markdown> scheduleRow(String what, String cron, String whenThereIsNone) {
        if (cron == null || cron.isBlank()) {
            return List.of(Md.text(what), Md.italic(whenThereIsNone), Md.text(""));
        }
        return List.of(Md.text(what), Md.code(cron), Md.text(""));
    }

    private static Markdown yesOrNo(boolean value) {
        return Md.text(value ? "yes" : "no");
    }
}
