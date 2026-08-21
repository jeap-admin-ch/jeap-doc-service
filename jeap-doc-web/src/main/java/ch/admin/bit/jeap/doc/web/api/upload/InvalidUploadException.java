package ch.admin.bit.jeap.doc.web.api.upload;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An upload the doc service refuses to accept.
 * <p>
 * The {@link Code} travels to the caller as an extension member of the problem response, so a pipeline can react
 * on the reason instead of on the message.
 */
@Getter
class InvalidUploadException extends RuntimeException {

    enum Code {

        MISSING_PARAMETER(HttpStatus.BAD_REQUEST),
        UNKNOWN_PARAMETER(HttpStatus.BAD_REQUEST),
        INVALID_PARAMETER_VALUE(HttpStatus.BAD_REQUEST),
        SIZE_LIMIT_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        HttpStatus status() {
            return status;
        }
    }

    private final transient Code code;

    InvalidUploadException(Code code, String message) {
        super(message);
        this.code = code;
    }

    static InvalidUploadException missing(String parameter, String requiredBecause) {
        return new InvalidUploadException(Code.MISSING_PARAMETER,
                "The parameter '%s' is required %s.".formatted(parameter, requiredBecause));
    }

    static InvalidUploadException unknown(String parameter, String knownParameters) {
        return new InvalidUploadException(Code.UNKNOWN_PARAMETER,
                "Unknown parameter '%s', expected one of: %s.".formatted(parameter, knownParameters));
    }

    static InvalidUploadException invalidValue(String parameter, String value, String expected) {
        return new InvalidUploadException(Code.INVALID_PARAMETER_VALUE,
                "The parameter '%s' has the invalid value '%s', expected: %s.".formatted(parameter, value, expected));
    }

    static InvalidUploadException tooLarge(long limit) {
        return new InvalidUploadException(Code.SIZE_LIMIT_EXCEEDED,
                "The uploaded bundle is larger than the accepted %d bytes.".formatted(limit));
    }
}
