package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.web.api.UploadProblems;
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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URI;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

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

    static final String PROBLEM_TYPE = UploadProblems.PROBLEM_TYPE;

    /**
     * The codes that are raised before the domain ever sees the upload - a parameter that is missing, unknown or
     * wrong, or a request that announced no length. They can never appear in the upload timer, because nothing
     * was timed, and a typo in a workflow configuration would otherwise be invisible.
     * <p>
     * <b>Everything else is counted by the domain</b>, which timed it. Counting it here as well would count one
     * outcome twice - the same trap the logging rule of this class guards against.
     */
    private static final Set<InvalidUploadException.Code> REJECTED_BEFORE_THE_DOMAIN = EnumSet.of(
            InvalidUploadException.Code.MISSING_PARAMETER,
            InvalidUploadException.Code.UNKNOWN_PARAMETER,
            InvalidUploadException.Code.INVALID_PARAMETER_VALUE,
            InvalidUploadException.Code.LENGTH_REQUIRED);

    private final MeterRegistry meterRegistry;

    UploadExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(InvalidUploadException.class)
    ResponseEntity<ProblemDetail> handleInvalidUpload(InvalidUploadException exception, HttpServletRequest request) {
        logRejection(exception.getCode(), exception.getMessage(), request);
        countIfRejectedBeforeTheDomain(exception.getCode());
        ProblemDetail problem = problem(exception.getCode(), exception.getMessage());
        if (exception.getReport() != null) {
            // A set refused over its structure is answered with the findings, in the same shape the
            // validation endpoint answers them: a pipeline that prints them should not have to know which of
            // the two refused the set.
            StructureReportDto.of(exception.getReport()).into(problem);
        }
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
        countIfRejectedBeforeTheDomain(InvalidUploadException.Code.MISSING_PARAMETER);
        return problem(InvalidUploadException.Code.MISSING_PARAMETER, detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        String detail = "The parameter '%s' has a value that cannot be read as %s."
                .formatted(exception.getName(), describeRequiredType(exception));
        logRejection(InvalidUploadException.Code.INVALID_PARAMETER_VALUE, detail, request);
        countIfRejectedBeforeTheDomain(InvalidUploadException.Code.INVALID_PARAMETER_VALUE);
        return problem(InvalidUploadException.Code.INVALID_PARAMETER_VALUE, detail);
    }

    /** Counts an upload that was refused before the doc service read anything of it. */
    private void countIfRejectedBeforeTheDomain(InvalidUploadException.Code code) {
        if (!REJECTED_BEFORE_THE_DOMAIN.contains(code)) {
            return;
        }
        Counter.builder("jeap.doc.upload.rejected")
                .description("Uploads rejected before the doc service read anything of them")
                .tag("reason", code.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
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
        // Through forLog, because a detail quotes what the request carried - the name of an unknown parameter
        // among it - and a line break in that would look like a second log entry.
        String forLog = UploadProblems.forLog(detail);
        if (code == InvalidUploadException.Code.STORAGE_FAILED) {
            log.debug("Answering the upload {} with {}: {}", uploadIdOf(request), code, forLog);
        } else if (code == InvalidUploadException.Code.UPLOAD_IN_PROGRESS) {
            log.info("Refused the upload {} of the system {}: {} - {}",
                    uploadIdOf(request), systemOf(request), code, forLog);
        } else {
            log.warn("Rejected the upload {} of the system {}: {} - {}",
                    uploadIdOf(request), systemOf(request), code, forLog);
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
        return system == null ? "?" : UploadProblems.forLog(system);
    }

    private static String describeRequiredType(MethodArgumentTypeMismatchException exception) {
        Class<?> requiredType = exception.getRequiredType();
        return requiredType == null ? "the expected type" : requiredType.getSimpleName();
    }

    /** One document for both advices below this path - see {@link UploadProblems}. */
    private static ProblemDetail problem(InvalidUploadException.Code code, String detail) {
        return UploadProblems.of(code, detail);
    }
}
