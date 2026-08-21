package ch.admin.bit.jeap.doc.web.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/**
 * Turns a rejected upload into an RFC 9457 problem response carrying the machine-readable reason, so a pipeline
 * can tell a misconfigured upload from a failing service.
 */
@Slf4j
@RestControllerAdvice
public class DocsUploadExceptionHandler {

    static final String PROBLEM_TYPE = "https://jeap.admin.ch/problems/docs/invalid-upload";

    @ExceptionHandler(InvalidUploadException.class)
    ProblemDetail handleInvalidUpload(InvalidUploadException exception) {
        log.debug("Rejected an upload: {}", exception.getMessage());
        return problem(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException exception) {
        log.debug("Rejected an upload without the parameter '{}'", exception.getParameterName());
        return problem(InvalidUploadException.Code.MISSING_PARAMETER,
                "The parameter '%s' is required.".formatted(exception.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.debug("Rejected an upload with an unreadable value of the parameter '{}'", exception.getName());
        return problem(InvalidUploadException.Code.INVALID_PARAMETER_VALUE,
                "The parameter '%s' has a value that cannot be read as %s."
                        .formatted(exception.getName(), describeRequiredType(exception)));
    }

    private static String describeRequiredType(MethodArgumentTypeMismatchException exception) {
        Class<?> requiredType = exception.getRequiredType();
        return requiredType == null ? "the expected type" : requiredType.getSimpleName();
    }

    private static ProblemDetail problem(InvalidUploadException.Code code, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problemDetail.setType(URI.create(PROBLEM_TYPE));
        problemDetail.setTitle("The upload does not describe a documentation set");
        problemDetail.setProperty("code", code.name());
        return problemDetail;
    }
}
