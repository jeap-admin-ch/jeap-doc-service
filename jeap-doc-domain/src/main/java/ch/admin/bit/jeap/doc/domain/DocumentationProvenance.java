package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles what the doc service may say about itself in public.
 * <p>
 * <b>The one place that decides what is publishable.</b> The rows this reads carry values a public page may not
 * show - most sharply the reason an import run failed, which is built from what the upstream answered and quotes
 * its host and its paths. None of them reaches {@link DocumentationFacts}, so nothing downstream has to remember
 * to leave them out: the page writer prints what it is handed, and this is what hands it to it.
 * <p>
 * It answers two questions from the same rows. {@link DocumentationFacts} is what a build writes into a page,
 * and it has to keep reading correctly while that page is not rebuilt. {@link DocumentationLiveStatus} is what
 * the service answers now, for the statements that would go stale in a page - and it is published under the
 * same rule.
 * <p>
 * It reads and computes; it stores nothing. Two builds asking for the facts get two answers, and neither
 * changes anything.
 */
@Service
@RequiredArgsConstructor
public class DocumentationProvenance {

    private final DocumentationSites sites;
    private final ArchitectureImportRepository imports;
    private final ArchitectureModelSource architectureModel;
    private final StructureTemplates templates;
    private final BuildProperties buildProperties;
    private final ArchitectureImportProperties importProperties;
    private final Clock clock;

    /**
     * The facts of one site, or empty where no such site is configured.
     *
     * @param siteId      the site to describe
     * @param version     the version of the doc service, or null where it cannot be read
     * @param generatedAt when the build asking for this started, which is what the page says it was written at
     */
    public Optional<DocumentationFacts> of(String siteId, String version, Instant generatedAt) {
        return sites.find(siteId).map(site -> factsOf(site, version, generatedAt));
    }

    /**
     * The live state of one site's imports and schedules, or empty where no such site is configured.
     * <p>
     * The same rows as {@link #of}, read at the moment of the request rather than at the start of a build -
     * which is the whole point of it. See {@link DocumentationLiveStatus} for why the page cannot carry this.
     */
    public Optional<DocumentationLiveStatus> liveStatusOf(String siteId) {
        return sites.find(siteId).map(this::liveStatusOf);
    }

    private DocumentationLiveStatus liveStatusOf(Site site) {
        Instant now = clock.instant();
        List<DocumentationLiveStatus.EnvironmentStatus> environments = environmentsOf(site).stream()
                .map(environment -> new DocumentationLiveStatus.EnvironmentStatus(
                        environment.id(), environment.modelConfigured(), environment.lastImportAt(),
                        environment.lastImportOutcome(), environment.importIsBehind(now),
                        lastReadOf(environment, now)))
                .toList();
        DocumentationFacts.Schedules schedules = schedulesOf(site);
        List<DocumentationLiveStatus.ScheduleStatus> tabulated = schedules.import_() == null
                || schedules.importAt() == null
                ? List.of()
                : List.of(new DocumentationLiveStatus.ScheduleStatus(schedules.import_(), schedules.importAt(),
                        whenItFiresNext(schedules.importAt(), now)));
        return new DocumentationLiveStatus(site.id(), now, environments, tabulated);
    }

    /**
     * When the architecture repository was last read successfully, and what has happened since.
     * <p>
     * The outcome belongs to the <b>latest</b> run and the timestamp to the last <b>successful</b> one, so the
     * two are not joined into one phrase: a repository that has been down since eleven would otherwise read
     * "last read 10:00 (failed)", which says the read that worked did not.
     * <p>
     * <b>Being behind is named whatever the latest outcome was.</b> An import that simply stopped running
     * leaves a successful outcome behind it, and that is the case worth reporting: nothing else on a published
     * site would say the documentation has stopped moving.
     */
    private static String lastReadOf(DocumentationFacts.EnvironmentFacts environment, Instant now) {
        if (!environment.modelConfigured()) {
            return "";
        }
        if (environment.lastImportAt() == null) {
            return "not read successfully yet";
        }
        String read = DisplayTime.of(environment.lastImportAt());
        if (environment.importIsBehind(now)) {
            return read + "; not read since";
        }
        return succeeded(environment.lastImportOutcome()) ? read : read + "; the last run did not read it";
    }

    /** Whether an outcome is one that read the repository through - the two the staleness measure counts. */
    private static boolean succeeded(ImportOutcome outcome) {
        return outcome == null || outcome == ImportOutcome.REPLACED || outcome == ImportOutcome.UNCHANGED;
    }

    /** When a schedule fires next, as the cell reads it. */
    static String whenItFiresNext(Instant next, Instant now) {
        return DisplayTime.of(next) + " (" + spellOut(Duration.between(now, next)) + ")";
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

    private DocumentationFacts factsOf(Site site, String version, Instant generatedAt) {
        return new DocumentationFacts(
                new DocumentationFacts.Service(version, generatedAt),
                siteFactsOf(site),
                environmentsOf(site),
                schedulesOf(site));
    }

    private DocumentationFacts.SiteFacts siteFactsOf(Site site) {
        return new DocumentationFacts.SiteFacts(site.id(), site.title(),
                templates.all().stream().map(StructureTemplate::systemLabel).toList(),
                site.architectureModelRequired(), site.publishOnUpload(), buildProperties.getRetention());
    }

    private List<DocumentationFacts.EnvironmentFacts> environmentsOf(Site site) {
        List<DocumentationFacts.EnvironmentFacts> environments = new ArrayList<>();
        for (SiteEnvironment environment : site.environments()) {
            boolean configured = architectureModel.isConfiguredFor(environment.id());
            ArchitectureImportState state = imports.state(environment.id(), ArchitectureImportKind.MODEL);
            environments.add(new DocumentationFacts.EnvironmentFacts(environment.id(), environment.label(),
                    environment.main(), environment.latest(), configured, state.lastSuccessAt(),
                    state.lastOutcome(), importProperties.getStaleAfter()));
        }
        return environments;
    }

    /**
     * What a reader wants to know is when the content changes, and that is the import: it asks for every part
     * of every site documenting the environment it read, so a site has no publication schedule of its own.
     * The import schedule is instance-wide rather than per site, and it belongs on every site's page.
     */
    private DocumentationFacts.Schedules schedulesOf(Site site) {
        String importCron = importProperties.getCron();
        boolean anyEnvironmentReadsAModel = site.environments().stream()
                .anyMatch(environment -> architectureModel.isConfiguredFor(environment.id()));
        return new DocumentationFacts.Schedules(
                anyEnvironmentReadsAModel ? importCron : null,
                anyEnvironmentReadsAModel ? NextOccurrence.of(importCron, clock).orElse(null) : null);
    }

}
