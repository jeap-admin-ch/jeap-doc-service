package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
     * The schedules of this site: its own publication schedule, and the import that feeds it.
     * <p>
     * The import schedule is instance-wide rather than per site, and it belongs on every site's page all the
     * same: what a reader wants to know is when the content changes, and for a site with an architecture
     * repository that is the import as much as the publication.
     */
    private DocumentationFacts.Schedules schedulesOf(Site site) {
        String publication = site.schedule().orElse(null);
        String importCron = importProperties.getCron();
        boolean anyEnvironmentReadsAModel = site.environments().stream()
                .anyMatch(environment -> architectureModel.isConfiguredFor(environment.id()));
        return new DocumentationFacts.Schedules(
                publication, NextOccurrence.of(publication, clock).orElse(null),
                anyEnvironmentReadsAModel ? importCron : null,
                anyEnvironmentReadsAModel ? NextOccurrence.of(importCron, clock).orElse(null) : null);
    }

}
