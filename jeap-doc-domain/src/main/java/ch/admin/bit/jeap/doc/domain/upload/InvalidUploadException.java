package ch.admin.bit.jeap.doc.domain.upload;

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

        /**
         * A structure validation carrying more paths than {@code jeap.doc.upload.validation.max-paths}. A
         * tree of that size is a mistake in the workflow configuration rather than a documentation set, so
         * the request is refused instead of answered.
         */
        TOO_MANY_PATHS,
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

    /**
     * More paths than one validation request may carry. Refused rather than answered: the answer would be a
     * report about a tree that is a mistake in the workflow configuration.
     * <p>
     * <i>More than</i> rather than how many: the cap is applied while the body is read, and counting them all
     * would mean having read them all.
     */
    public static InvalidUploadException tooManyPaths(int limit) {
        return new InvalidUploadException(Code.TOO_MANY_PATHS,
                ("The documentation set carries more than %d paths, and at most that many may be validated in "
                 + "one request. A set of that size is a path pointing at more than the documentation.")
                        .formatted(limit));
    }

    /**
     * A validation request whose body is not a path tree - malformed JSON, no body, or a {@code paths} that is
     * not an array of strings.
     * <p>
     * The parser's own message is deliberately not passed on: what is wrong with the JSON is in the caller's
     * own body, and an internal message tells them nothing they can act on.
     */
    public static InvalidUploadException bodyIsNotAPathTree() {
        return new InvalidUploadException(Code.INVALID_PARAMETER_VALUE,
                "The request body is not a readable path tree.");
    }

    public static InvalidUploadException tooLarge(long limit) {
        return new InvalidUploadException(Code.SIZE_LIMIT_EXCEEDED,
                "The uploaded bundle is larger than the accepted %d bytes.".formatted(limit));
    }

    /**
     * A validation request whose body is larger than the paths it may carry could ever be.
     * <p>
     * Refused <b>before the body is read at all</b>, on the announced length, which is what makes it
     * different from {@link #tooManyPaths}: that one is reached while reading the paths.
     */
    public static InvalidUploadException bodyTooLarge(long announced, long limit) {
        return new InvalidUploadException(Code.SIZE_LIMIT_EXCEEDED,
                ("The request body announces %d bytes, and at most %d can be a list of paths this endpoint "
                 + "would accept.").formatted(announced, limit));
    }
}
