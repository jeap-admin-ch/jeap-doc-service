package ch.admin.bit.jeap.doc.domain.port;

/**
 * The architecture model of an environment could not be read, so the documentation cannot be generated from it.
 * <p>
 * It fails the build on purpose. Generating what could be read would publish a site with systems missing and
 * every link to them dead, which is worse than a site that is an hour old. The site published before it stays
 * served until the next run succeeds.
 */
public class ArchitectureModelUnavailableException extends RuntimeException {

    public ArchitectureModelUnavailableException(String message) {
        super(message);
    }

    public ArchitectureModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
