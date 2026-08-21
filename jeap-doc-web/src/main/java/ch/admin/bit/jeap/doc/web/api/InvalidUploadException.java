package ch.admin.bit.jeap.doc.web.api;

import lombok.Getter;

/**
 * An upload the doc service refuses to accept because its parameters do not describe a documentation set.
 * <p>
 * The {@link Code} travels to the caller as an extension member of the problem response, so a pipeline can react
 * on the reason instead of on the message.
 */
@Getter
public class InvalidUploadException extends RuntimeException {

    public enum Code {
        MISSING_PARAMETER,
        UNKNOWN_PARAMETER,
        INVALID_PARAMETER_VALUE
    }

    private final transient Code code;

    public InvalidUploadException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static InvalidUploadException missing(String parameter, String requiredBecause) {
        return new InvalidUploadException(Code.MISSING_PARAMETER,
                "The parameter '%s' is required %s.".formatted(parameter, requiredBecause));
    }

    public static InvalidUploadException unknown(String parameter, String knownParameters) {
        return new InvalidUploadException(Code.UNKNOWN_PARAMETER,
                "Unknown parameter '%s', expected one of: %s.".formatted(parameter, knownParameters));
    }

    public static InvalidUploadException invalidValue(String parameter, String value, String expected) {
        return new InvalidUploadException(Code.INVALID_PARAMETER_VALUE,
                "The parameter '%s' has the invalid value '%s', expected: %s.".formatted(parameter, value, expected));
    }
}
