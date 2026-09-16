package ch.admin.bit.jeap.doc.web.api.architecture;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportJob;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportQueue;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
import ch.admin.bit.jeap.doc.web.api.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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
    /** The import states it reports, which may lag a moment - see {@link DisplayReads}. */
    private final DisplayReads reads;
    private final ArchitectureModelSource architectureModel;

    /** Where an ask is put: it collapses a second ask for one environment and reports a full queue. */
    private final ArchitectureImportQueue queue;

    ArchitectureAdminController(ArchitectureImportJob job, DisplayReads reads,
                                ArchitectureModelSource architectureModel, ArchitectureImportQueue queue) {
        this.job = job;
        this.reads = reads;
        this.architectureModel = architectureModel;
        this.queue = queue;
    }

    @Operation(summary = "Ask for every environment to be imported",
            description = "Puts an import of every configured environment on this instance's import queue and "
                          + "answers 202. Nothing runs on this request: the imports take minutes and run one "
                          + "after the other on one thread. Use it after correcting something in an "
                          + "architecture repository - a documentation build reads what was imported and "
                          + "calls the architecture repository not at all, so forcing a publication alone "
                          + "would publish the old model again. An environment already on the queue is not "
                          + "queued twice, and the answer says so per environment; an environment being "
                          + "imported right now is imported once more afterwards, because this ask may be "
                          + "about something that run has already read. The ask is not durable: it is lost "
                          + "if this instance stops before it runs, and the schedule imports the environment "
                          + "anyway. 429 if nothing could be put on the queue at all.")
    @PostMapping(path = ArchitectureApiPaths.IMPORTS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    public ResponseEntity<ImportRequestedDto> requestEveryImport(Authentication caller) {
        List<String> environments = requireConfigured();
        ImportRequestedDto asked = enqueue(environments);
        log.info("An import of every architecture repository was asked for over the API by {}: {} queued, {} "
                 + "already asked for, {} refused.", nameOf(caller), asked.environments(),
                asked.alreadyAskedFor(), asked.refused());
        return answer(asked);
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
        ImportRequestedDto asked = enqueue(List.of(configured));
        log.info("An import of the architecture repository of the environment {} was asked for over the API "
                 + "by {}: {}.", configured, nameOf(caller), outcomeOf(asked));
        return answer(asked);
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
                                .map(kind -> ImportStateDto.of(reads.importState(environment, kind)))
                                .toList()))
                .toList();
    }

    /**
     * Hands the imports to the queue and sorts the environments by what became of each.
     * <p>
     * An environment <b>already on the queue</b> is not queued again: the same fetch behind itself is a minute
     * of the one import thread for nothing, and it is how repeated asks push the scheduled imports off a
     * bounded queue. An environment <b>being imported</b> is a different case and runs once more - see
     * {@code ArchitectureImportQueue}.
     */
    private ImportRequestedDto enqueue(List<String> environments) {
        List<String> queued = new ArrayList<>();
        List<String> alreadyAskedFor = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (String environment : environments) {
            switch (queue.submit(environment)) {
                case QUEUED -> queued.add(environment);
                case ALREADY_ASKED_FOR -> alreadyAskedFor.add(environment);
                case REFUSED -> refused.add(environment);
            }
        }
        return ImportRequestedDto.of(queued, alreadyAskedFor, refused);
    }

    /**
     * <b>202 while an import of everything asked for is coming</b> - queued now, or already on its way. Only
     * where nothing is is the answer 429: the queue is full, the operator's ask did nothing, and telling them
     * 202 would be a lie about the one thing they are waiting for.
     */
    private static ResponseEntity<ImportRequestedDto> answer(ImportRequestedDto asked) {
        if (asked.environments().isEmpty() && asked.alreadyAskedFor().isEmpty()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(asked);
        }
        return ResponseEntity.accepted().body(asked);
    }

    private static String outcomeOf(ImportRequestedDto asked) {
        if (!asked.environments().isEmpty()) {
            return "queued";
        }
        return asked.refused().isEmpty() ? "already asked for" : "refused, the queue is full";
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
