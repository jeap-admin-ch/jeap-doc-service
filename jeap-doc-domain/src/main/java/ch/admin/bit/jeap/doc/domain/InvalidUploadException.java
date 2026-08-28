package ch.admin.bit.jeap.doc.domain;

import lombok.Getter;

import java.time.Duration;

/**
 * An upload the doc service refuses to accept.
 * <p>
 * The {@link Code} says why, in a form a pipeline can react on instead of parsing a message. How a code reaches
 * the caller - which HTTP status it is answered with - is the business of the web layer.
 */
@Getter
public class InvalidUploadException extends RuntimeException {

    public enum Code {

        MISSING_PARAMETER,
        UNKNOWN_PARAMETER,
        INVALID_PARAMETER_VALUE,
        /**
         * The upload names a documentation site this instance does not configure. Its own code rather than an
         * invalid parameter value, because it is the one of these the domain raises: which sites exist is
         * configuration the web layer cannot see, so it is answered after the upload has been timed and must
         * not also be counted as a rejection that happened before the domain.
         */
        UNKNOWN_SITE,
        SIZE_LIMIT_EXCEEDED,
        LENGTH_REQUIRED,
        CONTENT_LENGTH_MISMATCH,
        UPLOAD_IN_PROGRESS,
        UPLOAD_ID_CONFLICT,
        STORAGE_FAILED
    }

    private final transient Code code;

    /**
     * How long the caller should wait before repeating the request, if waiting is what helps.
     */
    private final transient Duration retryAfter;

    public InvalidUploadException(Code code, String message) {
        this(code, message, null, null);
    }

    public InvalidUploadException(Code code, String message, Throwable cause) {
        this(code, message, cause, null);
    }

    private InvalidUploadException(Code code, String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public static InvalidUploadException inProgress(String message, Duration retryAfter) {
        return new InvalidUploadException(Code.UPLOAD_IN_PROGRESS, message, null, retryAfter);
    }

    public static InvalidUploadException missing(String parameter, String requiredBecause) {
        return new InvalidUploadException(Code.MISSING_PARAMETER,
                "The parameter '%s' is required %s.".formatted(parameter, requiredBecause));
    }

    public static InvalidUploadException unknown(String parameter, String knownParameters) {
        return new InvalidUploadException(Code.UNKNOWN_PARAMETER,
                "Unknown parameter '%s', expected one of: %s.".formatted(parameter, knownParameters));
    }

    public static InvalidUploadException unknownSite(String site, Object configuredSites) {
        return new InvalidUploadException(Code.UNKNOWN_SITE,
                ("The documentation site '%s' is not one this doc service is configured with: %s. Which sites "
                 + "exist is configuration, so a site nobody configured is refused rather than published "
                 + "nowhere.").formatted(site, configuredSites));
    }

    public static InvalidUploadException invalidValue(String parameter, String value, String expected) {
        return new InvalidUploadException(Code.INVALID_PARAMETER_VALUE,
                "The parameter '%s' has the invalid value '%s', expected: %s.".formatted(parameter, value, expected));
    }

    public static InvalidUploadException tooLarge(long limit) {
        return new InvalidUploadException(Code.SIZE_LIMIT_EXCEEDED,
                "The uploaded bundle is larger than the accepted %d bytes.".formatted(limit));
    }
}
