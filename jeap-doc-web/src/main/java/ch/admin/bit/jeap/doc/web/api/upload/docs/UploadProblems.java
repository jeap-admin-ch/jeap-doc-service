package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * The RFC 9457 problem document a rejected request below {@code /api/uploads/docs} is answered with.
 * <p>
 * <b>Extracted so that two advices produce one shape.</b> The upload has one and the structure validation has
 * another - the second must not increment {@code jeap.doc.upload.rejected}, because nothing was uploaded - and
 * a pipeline still has to parse one document whichever endpoint refused it.
 * <p>
 * The domain says <i>why</i>; this says <i>with which status</i>. That mapping is the web layer's decision.
 */
final class UploadProblems {

    static final String PROBLEM_TYPE = "https://jeap.admin.ch/problems/docs/invalid-upload";

    private UploadProblems() {
    }

    static ProblemDetail of(InvalidUploadException.Code code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusOf(code), detail);
        problem.setType(URI.create(PROBLEM_TYPE));
        problem.setTitle("The upload does not describe a documentation set");
        problem.setProperty("code", code.name());
        return problem;
    }

    static HttpStatus statusOf(InvalidUploadException.Code code) {
        return switch (code) {
            case MISSING_PARAMETER, UNKNOWN_PARAMETER, INVALID_PARAMETER_VALUE, UNKNOWN_SITE,
                 CONTENT_LENGTH_MISMATCH -> HttpStatus.BAD_REQUEST;
            case UPLOAD_IN_PROGRESS, UPLOAD_ID_CONFLICT -> HttpStatus.CONFLICT;
            case LENGTH_REQUIRED -> HttpStatus.LENGTH_REQUIRED;
            case SIZE_LIMIT_EXCEEDED, TOO_MANY_PATHS -> HttpStatus.PAYLOAD_TOO_LARGE;
            case STORAGE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
