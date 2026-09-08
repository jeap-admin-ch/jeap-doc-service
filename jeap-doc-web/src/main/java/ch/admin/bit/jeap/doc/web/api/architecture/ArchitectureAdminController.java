package ch.admin.bit.jeap.doc.web.api.architecture;

import ch.admin.bit.jeap.doc.domain.DocDomainConfiguration;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportJob;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.web.api.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Asks for the architecture repository to be imported, and reports what its imports have been doing.
 * <p>
 * <b>Why an operator needs this.</b> The model is imported on a schedule and a build reads what was stored, so
 * a correction made in the architecture repository is invisible to the documentation until the next hour - and
 * forcing a publication does not help, because a build calls the architecture repository not at all. Without
 * this endpoint the only way to bring a correction forward was to restart an instance and let the catch-up
 * import run.
 * <p>
 * <b>Asking is not importing.</b> The request is handed to the same single-threaded executor the schedule and
 * the startup catch-up use, and the thread is back within a millisecond. An import takes minutes, so running
 * it on the request thread would hold a connection for the whole of it - and running two at once would fetch
 * two landscapes into a heap sized for one.
 * <p>
 * <b>The ask is not durable</b>, unlike a build request, which is a row. The queue is this instance's own, so
 * an ask is lost if the instance stops before it runs; the schedule imports the environment anyway at its next
 * occurrence. The answer says so.
 */
@Slf4j
@RestController
@Tag(name = "architecture", description = "Import of the architecture repository")
class ArchitectureAdminController {

    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    private final ArchitectureImportJob job;
    private final ArchitectureImportRepository imports;
    private final ArchitectureModelSource architectureModel;

    /**
     * The executor every import runs on, named rather than "whatever {@code TaskExecutor} this context has" -
     * an instance may add starters that contribute executors of their own. Written out rather than generated,
     * because the qualifier has to reach the constructor <b>parameter</b> and Lombok does not carry it there.
     */
    private final TaskExecutor taskExecutor;

    ArchitectureAdminController(ArchitectureImportJob job, ArchitectureImportRepository imports,
                                ArchitectureModelSource architectureModel,
                                @Qualifier(DocDomainConfiguration.ARCHITECTURE_IMPORT_TASK_EXECUTOR)
                                TaskExecutor taskExecutor) {
        this.job = job;
        this.imports = imports;
        this.architectureModel = architectureModel;
        this.taskExecutor = taskExecutor;
    }

    @Operation(summary = "Ask for every environment to be imported",
            description = "Puts an import of every configured environment on this instance's import queue and "
                          + "answers 202. Nothing runs on this request: the imports take minutes and run one "
                          + "after the other on one thread. Use it after correcting something in an "
                          + "architecture repository - a documentation build reads what was imported and "
                          + "calls the architecture repository not at all, so forcing a publication alone "
                          + "would publish the old model again. The ask is not durable: it is lost if this "
                          + "instance stops before it runs, and the schedule imports the environment anyway.")
    @PostMapping(path = ArchitectureApiPaths.IMPORTS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    public ResponseEntity<ImportRequestedDto> requestEveryImport(Authentication caller) {
        List<String> environments = requireConfigured();
        environments.forEach(this::enqueue);
        log.info("An import of every architecture repository was asked for over the API by {}: {}.",
                nameOf(caller), environments);
        return ResponseEntity.accepted().body(ImportRequestedDto.of(environments));
    }

    @Operation(summary = "Ask for one environment to be imported",
            description = "Puts an import of one environment on this instance's import queue and answers 202. "
                          + "Nothing runs on this request; see the endpoint for every environment.")
    @PostMapping(path = ArchitectureApiPaths.ENVIRONMENT_IMPORTS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    public ResponseEntity<ImportRequestedDto> requestImport(
            @Parameter(description = "Identifier of the environment") @PathVariable String environment,
            Authentication caller) {
        String configured = requireConfigured(environment);
        enqueue(configured);
        log.info("An import of the architecture repository of the environment {} was asked for over the API "
                 + "by {}.", configured, nameOf(caller));
        return ResponseEntity.accepted().body(ImportRequestedDto.of(List.of(configured)));
    }

    @Operation(summary = "Read the state of the architecture imports",
            description = "Answers one entry per configured environment, with what the last import of each "
                          + "kind did. The model comes first: it is what decides which systems and components "
                          + "exist, and therefore which artifacts are orphans.")
    @GetMapping(path = ArchitectureApiPaths.ENVIRONMENTS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_READ_ROLE)
    public List<EnvironmentImportsDto> environments() {
        return job.environments().stream()
                .map(environment -> new EnvironmentImportsDto(environment,
                        architectureModel.sourceUrlOf(environment).orElse(""),
                        // The model first, then the artifact kinds in the order they are imported in.
                        Arrays.stream(ArchitectureImportKind.values())
                                .map(kind -> ImportStateDto.of(imports.state(environment, kind)))
                                .toList()))
                .toList();
    }

    /**
     * Hands the import to the executor. <b>The rejection of a full queue is the executor's own business</b>:
     * it logs and drops, because the next schedule imports what this ask did not - so there is nothing here to
     * answer differently, and a queue check before the call would be a race either way.
     */
    private void enqueue(String environment) {
        taskExecutor.execute(() -> job.importEnvironment(environment));
    }

    private List<String> requireConfigured() {
        List<String> environments = job.environments();
        if (environments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No architecture repository is configured, so there is nothing to import. Configure "
                    + "jeap.doc.archrepo.environments.<environment>.");
        }
        return environments;
    }

    /**
     * The environment as it is configured, or {@code 404}. Checked against what is configured rather than
     * taken from the path: an import of an environment nobody reads would take a lock, log a failure and
     * puzzle whoever asked - and the typo is the likely reason for asking twice.
     */
    private String requireConfigured(String environment) {
        return requireConfigured().stream()
                .filter(configured -> configured.equals(environment))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No architecture repository is configured for the environment '"
                        + LINE_BREAK.matcher(environment).replaceAll("_") + "'."));
    }

    private static String nameOf(Authentication caller) {
        return caller == null ? "?" : LINE_BREAK.matcher(caller.getName()).replaceAll("_");
    }
}
