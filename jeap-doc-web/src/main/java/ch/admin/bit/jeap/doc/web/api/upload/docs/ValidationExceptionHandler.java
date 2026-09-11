package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.web.api.UploadProblems;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a validation request that is itself wrong into the same problem document an upload is refused with.
 * <p>
 * <b>Its own advice, and the reason is one meter.</b> {@code UploadExceptionHandler} counts
 * {@code jeap.doc.upload.rejected} for a parameter that is missing, unknown or wrong - and a validation that
 * never uploaded anything must not appear there, or the number that says <i>how many uploads were refused
 * before the doc service read them</i> would count questions as uploads. Everything else about the two is the
 * same, which is what {@link UploadProblems} is for.
 * <p>
 * Scoped to the {@link DocumentationValidationController}, because the framework exceptions handled here occur
 * on every endpoint.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = DocumentationValidationController.class)
class ValidationExceptionHandler {

    @ExceptionHandler(InvalidUploadException.class)
    ResponseEntity<ProblemDetail> handleInvalid(InvalidUploadException exception) {
        // Through forLog: a detail quotes what the request carried, and a line break in it would look like a
        // second log entry.
        log.debug("Rejected a structure validation: {} - {}", exception.getCode(),
                UploadProblems.forLog(exception.getMessage()));
        ProblemDetail problem = UploadProblems.of(exception.getCode(), exception.getMessage());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException exception) {
        String detail = "The parameter '%s' is required.".formatted(exception.getParameterName());
        log.debug("Rejected a structure validation: MISSING_PARAMETER - {}", UploadProblems.forLog(detail));
        return UploadProblems.of(InvalidUploadException.Code.MISSING_PARAMETER, detail);
    }
}
