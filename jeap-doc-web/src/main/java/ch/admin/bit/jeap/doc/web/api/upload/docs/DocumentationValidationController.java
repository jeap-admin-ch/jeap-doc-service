package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureReport;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureValidation;
import ch.admin.bit.jeap.doc.web.api.Roles;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Answers whether a path tree would be accepted, before a pipeline builds a ZIP of it.
 * <p>
 * <b>Below {@code /api/uploads/docs}, because what is validated is a documentation upload.</b> A family of its
 * own would say the same twice and register a second copy of the parameter list; here the parameter
 * interceptor of that path already guards it, so a typo in a workflow configuration fails as loudly as it does
 * on the upload itself.
 * <p>
 * <b>The verdict is the status line.</b> A tree with no finding is {@code 200}; one with findings is
 * {@code 422} - the request was understood and its content cannot be processed, which is exactly the case -
 * and the body is then the problem document the upload API already answers with, carrying the report as
 * extension members. So a pipeline branches three ways: {@code 200} publish, {@code 422} print the findings and
 * stop, anything else fail loudly because the endpoint or the token is wrong.
 * <p>
 * <b>Nothing is stored and nothing is read.</b> The tree arrives as a list of paths, this endpoint has no side
 * effect, and it never sees a file's bytes - the content is the workflow's half of the validation.
 */
@Slf4j
@RestController
@RequestMapping(UploadPaths.DOCS)
@RequiredArgsConstructor
@Tag(name = "doc-uploads", description = "Upload of documentation")
class DocumentationValidationController {

    /** What a misfiled tree is answered with. Its own type: it is not the request that was wrong. */
    static final String PROBLEM_TYPE = "https://jeap.admin.ch/problems/docs/structure-invalid";

    /** The path the stricter parameter interceptor is registered on - see DocumentationUploadConfiguration. */
    static final String VALIDATION_PATH = "/validation";

    private final StructureValidation validation;
    private final UploadProperties uploadProperties;

    @Operation(summary = "Validate the structure of a documentation set",
            description = "Answers whether the path tree would be accepted, against the chapters, the "
                          + "extensions and the generated page names of the named structure template. Nothing "
                          + "is uploaded, stored or read: the tree arrives as a list of paths and the content "
                          + "of the files is the doc workflow's own half of the validation. Answers 200 when "
                          + "there is nothing to report and 422 with the findings when there is; the "
                          + "parameters this endpoint accepts are the ones the structure depends on, and a "
                          + "request carrying any other is rejected.")
    @PostMapping(path = VALIDATION_PATH, consumes = "application/json", produces = "application/json")
    @PreAuthorize(Roles.HAS_UPLOADS_WRITE_ROLE_FOR_SYSTEM)
    public ResponseEntity<Object> validate(
            @Parameter(description = "What the documents document: system-docs, component-docs or library-docs")
            @RequestParam("type") String type,
            @Parameter(description = "System the documents belong to, and the system the role is checked for")
            @RequestParam("system") String system,
            @Parameter(description = "Component the documents belong to, required for component-docs")
            @RequestParam(name = "component", required = false) String component,
            @Parameter(description = "Library the documents belong to, required for library-docs")
            @RequestParam(name = "library", required = false) String library,
            @Parameter(description = "Section catalog the documents follow, e.g. arc42")
            @RequestParam("template") String template,
            @Parameter(description = "Format of the documents: markdown or html")
            @RequestParam("source-format") String sourceFormat,
            @Parameter(description = "Section HTML documents are embedded in, e.g. 6-runtime-view")
            @RequestParam(name = "location", required = false) String location,
            @Parameter(description = "Slug identifying HTML documents within their section")
            @RequestParam(name = "topic", required = false) String topic,
            @RequestBody PathTreeDto body) {

        DocumentationPlacement placement = new DocumentationPlacement(
                DocumentationTypeDto.fromParameterValue(type).toDomain(), system, component, library, template,
                SourceFormatDto.fromParameterValue(sourceFormat).toDomain(), location, topic);
        // Not null: @RequestBody is required, so a request with no body is answered 400 before this runs.
        List<String> paths = body.pathsOrEmpty();
        int maxPaths = uploadProperties.getValidation().getMaxPaths();
        if (paths.size() > maxPaths) {
            throw InvalidUploadException.tooManyPaths(paths.size(), maxPaths);
        }

        StructureReport report = validation.validate(placement, paths);
        StructureReportDto answer = StructureReportDto.of(report);
        if (report.isValid()) {
            log.debug("The documentation set of {} follows {}: {} path(s) checked, {} ignored.",
                    system, report.template(), report.pathsChecked(), report.pathsIgnored());
            return ResponseEntity.ok(answer);
        }
        log.info("The documentation set of {} does not follow {}: {} problem(s) in {} path(s).",
                system, report.template(), report.findings().size() + report.findingsOmitted(),
                report.pathsChecked());
        return ResponseEntity.unprocessableEntity().body(problemOf(answer));
    }

    /**
     * The findings as the extension members of a problem document - which is what RFC 9457 extension members
     * are for, and it keeps one body shape for every non-2xx of this path.
     */
    private static ProblemDetail problemOf(StructureReportDto report) {
        int problems = report.findings().size() + report.findingsOmitted();
        // Without the paths clause when nothing was checked: an unknown template is refused before a single
        // path is looked at, and "1 problem in 0 paths" reads like a bug to whoever sent forty-two of them.
        String detail = report.pathsChecked() == 0
                ? "%d problem%s.".formatted(problems, problems == 1 ? "" : "s")
                : "%d problem%s in %d path%s.".formatted(problems, problems == 1 ? "" : "s",
                        report.pathsChecked(), report.pathsChecked() == 1 ? "" : "s");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, detail);
        problem.setType(URI.create(PROBLEM_TYPE));
        problem.setTitle("The documentation structure is invalid");
        problem.setProperty("template", report.template());
        problem.setProperty("pathsChecked", report.pathsChecked());
        problem.setProperty("pathsIgnored", report.pathsIgnored());
        problem.setProperty("allowedFolders", report.allowedFolders());
        problem.setProperty("allowedExtensions", report.allowedExtensions());
        problem.setProperty("findings", report.findings());
        problem.setProperty("findingsOmitted", report.findingsOmitted());
        return problem;
    }
}
