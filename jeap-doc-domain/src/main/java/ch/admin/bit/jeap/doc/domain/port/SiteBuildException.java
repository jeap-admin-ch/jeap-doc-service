package ch.admin.bit.jeap.doc.domain.port;

/**
 * The site could not be generated. Its message is recorded on the failed build and is what an operator reads,
 * so it says what went wrong rather than where.
 */
public class SiteBuildException extends RuntimeException {

    public SiteBuildException(String message) {
        super(message);
    }

    public SiteBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
