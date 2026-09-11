package ch.admin.bit.jeap.doc.web.api;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * The RFC 9457 problem document a rejected request speaking the doc workflow's vocabulary is answered with.
 * <p>
 * <b>Extracted so that the advices produce one shape.</b> The upload has one, the structure validation has
 * another - it must not increment {@code jeap.doc.upload.rejected}, because nothing was uploaded - and the
 * removal of a set has a third. A pipeline still has to parse one document whichever of them refused it, and
 * it is the same reasons and the same {@code code} values throughout.
 * <p>
 * The domain says <i>why</i>; this says <i>with which status</i>. That mapping is the web layer's decision.
 */
public final class UploadProblems {

    public static final String PROBLEM_TYPE = "https://jeap.admin.ch/problems/docs/invalid-upload";

    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    private UploadProblems() {
    }

    public static ProblemDetail of(InvalidUploadException.Code code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusOf(code), detail);
        problem.setType(URI.create(PROBLEM_TYPE));
        problem.setTitle("The upload does not describe a documentation set");
        problem.setProperty("code", code.name());
        return problem;
    }

    /**
     * A problem detail on its way into a log line.
     * <p>
     * A detail quotes what the request carried - a parameter name straight out of the query string, already
     * decoded by the container - so a line break in it would look like a second log entry. The document sent
     * back is untouched: it is a JSON string, where a line break is escaped and means nothing.
     */
    public static String forLog(String detail) {
        return detail == null ? "" : LINE_BREAK.matcher(detail).replaceAll("_");
    }

    public static HttpStatus statusOf(InvalidUploadException.Code code) {
        return switch (code) {
            case MISSING_PARAMETER, UNKNOWN_PARAMETER, INVALID_PARAMETER_VALUE, UNKNOWN_SITE,
                 CONTENT_LENGTH_MISMATCH, INVALID_BUNDLE -> HttpStatus.BAD_REQUEST;
            case UPLOAD_IN_PROGRESS, UPLOAD_ID_CONFLICT -> HttpStatus.CONFLICT;
            case LENGTH_REQUIRED -> HttpStatus.LENGTH_REQUIRED;
            case SIZE_LIMIT_EXCEEDED, TOO_MANY_PATHS, UNPACKS_TO_TOO_MUCH -> HttpStatus.PAYLOAD_TOO_LARGE;
            // The set describes itself well enough to be placed and would not be published as it is, which is
            // what 422 says. The findings travel with it - see the validation endpoint's own answer.
            case STRUCTURE_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case STORAGE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
