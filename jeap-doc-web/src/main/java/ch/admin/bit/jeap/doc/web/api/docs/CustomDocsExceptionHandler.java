package ch.admin.bit.jeap.doc.web.api.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.web.api.UploadProblems;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a removal that is itself wrong into the same problem document an upload is refused with.
 * <p>
 * <b>The removal speaks the upload's vocabulary, so it answers the upload's problems.</b> A caller of this is a
 * workflow that knows a placement: it names a type, a system, a template and a source format, and it gets them
 * wrong in exactly the ways an upload does. Without this advice every one of those - {@code type=componet-docs},
 * a {@code location} passed with Markdown, a site this instance does not serve - would surface as an unhandled
 * exception with no {@code code} in it, and a typo in a workflow configuration would read as a doc service that
 * is broken.
 * <p>
 * <b>Its own advice, and the reason is one meter.</b> {@code UploadExceptionHandler} counts
 * {@code jeap.doc.upload.rejected} for a parameter that is missing, unknown or wrong - and a removal that
 * uploaded nothing must not appear there, for the same reason the structure validation does not.
 * <p>
 * Scoped to the {@link CustomDocsAdminController}, because the framework exceptions handled here occur on every
 * endpoint.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = CustomDocsAdminController.class)
class CustomDocsExceptionHandler {

    @ExceptionHandler(InvalidUploadException.class)
    ResponseEntity<ProblemDetail> handleInvalid(InvalidUploadException exception) {
        // Through forLog: a detail quotes what the request carried, and a line break in it would look like a
        // second log entry.
        log.warn("Rejected a removal of custom documentation: {} - {}", exception.getCode(),
                UploadProblems.forLog(exception.getMessage()));
        ProblemDetail problem = UploadProblems.of(exception.getCode(), exception.getMessage());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException exception) {
        String detail = "The parameter '%s' is required.".formatted(exception.getParameterName());
        log.warn("Rejected a removal of custom documentation: MISSING_PARAMETER - {}",
                UploadProblems.forLog(detail));
        return UploadProblems.of(InvalidUploadException.Code.MISSING_PARAMETER, detail);
    }
}
