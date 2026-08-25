package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.InvalidUploadException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Turns a rejected upload into an RFC 9457 problem response carrying the machine-readable reason, so a pipeline
 * can tell a misconfigured upload from a failing service.
 * <p>
 * The domain says why an upload was rejected, this handler says with which status it is answered - the mapping
 * from the reason to HTTP is a decision of the web layer, not of the domain.
 * <p>
 * Scoped to the {@link DocumentationUploadController}: the framework exceptions handled here occur on every
 * endpoint, and an unrelated one must not answer with the problem type of an upload.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = DocumentationUploadController.class)
class UploadExceptionHandler {

    static final String PROBLEM_TYPE = "https://jeap.admin.ch/problems/docs/invalid-upload";

    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    @ExceptionHandler(InvalidUploadException.class)
    ResponseEntity<ProblemDetail> handleInvalidUpload(InvalidUploadException exception, HttpServletRequest request) {
        logRejection(exception.getCode(), exception.getMessage(), request);
        ProblemDetail problem = problem(exception.getCode(), exception.getMessage());
        BodyBuilder response = ResponseEntity.status(problem.getStatus());
        if (exception.getRetryAfter() != null) {
            // Seconds, as RFC 9110 defines the header - a pipeline that retries can wait for what it says.
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfter().toSeconds()));
        }
        return response.body(problem);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException exception,
                                         HttpServletRequest request) {
        String detail = "The parameter '%s' is required.".formatted(exception.getParameterName());
        logRejection(InvalidUploadException.Code.MISSING_PARAMETER, detail, request);
        return problem(InvalidUploadException.Code.MISSING_PARAMETER, detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        String detail = "The parameter '%s' has a value that cannot be read as %s."
                .formatted(exception.getName(), describeRequiredType(exception));
        logRejection(InvalidUploadException.Code.INVALID_PARAMETER_VALUE, detail, request);
        return problem(InvalidUploadException.Code.INVALID_PARAMETER_VALUE, detail);
    }

    /**
     * Every rejected upload leaves one line naming the upload it was, so a pipeline that reports a failed upload
     * can be found by the id it quotes.
     * <p>
     * A caller that got its request wrong is logged at warn - it is nothing the operators of the doc service can
     * do anything about, but the team that sent it has to be able to see it. An upload that is refused because
     * another attempt of it is running is not a mistake at all: retrying is what a pipeline is supposed to do, so
     * it stays at info. A storage that failed is the one case the operators do have to react to, and it is
     * logged where it happens, with its cause.
     */
    private static void logRejection(InvalidUploadException.Code code, String detail, HttpServletRequest request) {
        if (code == InvalidUploadException.Code.STORAGE_FAILED) {
            log.debug("Answering the upload {} with {}: {}", uploadIdOf(request), code, detail);
        } else if (code == InvalidUploadException.Code.UPLOAD_IN_PROGRESS) {
            log.info("Refused the upload {} of the system {}: {} - {}",
                    uploadIdOf(request), systemOf(request), code, detail);
        } else {
            log.warn("Rejected the upload {} of the system {}: {} - {}",
                    uploadIdOf(request), systemOf(request), code, detail);
        }
    }

    private static String uploadIdOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * The system as the request gave it - a rejection is logged before anything checked that it is a slug, and
     * the container has already decoded it, so a line break in it would look like a second log entry.
     */
    private static String systemOf(HttpServletRequest request) {
        String system = request.getParameter("system");
        return system == null ? "?" : LINE_BREAK.matcher(system).replaceAll("_");
    }

    private static String describeRequiredType(MethodArgumentTypeMismatchException exception) {
        Class<?> requiredType = exception.getRequiredType();
        return requiredType == null ? "the expected type" : requiredType.getSimpleName();
    }

    private static ProblemDetail problem(InvalidUploadException.Code code, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(statusOf(code), detail);
        problemDetail.setType(URI.create(PROBLEM_TYPE));
        problemDetail.setTitle("The upload does not describe a documentation set");
        problemDetail.setProperty("code", code.name());
        return problemDetail;
    }

    private static HttpStatus statusOf(InvalidUploadException.Code code) {
        return switch (code) {
            case MISSING_PARAMETER, UNKNOWN_PARAMETER, INVALID_PARAMETER_VALUE, CONTENT_LENGTH_MISMATCH ->
                    HttpStatus.BAD_REQUEST;
            case UPLOAD_IN_PROGRESS, UPLOAD_ID_CONFLICT -> HttpStatus.CONFLICT;
            case LENGTH_REQUIRED -> HttpStatus.LENGTH_REQUIRED;
            case SIZE_LIMIT_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case STORAGE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
