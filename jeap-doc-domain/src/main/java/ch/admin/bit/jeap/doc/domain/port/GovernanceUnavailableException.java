package ch.admin.bit.jeap.doc.domain.port;

/**
 * The Governance Service's {@code /docs-api} could not be read, so the governance import could not run.
 * <p>
 * Thrown rather than swallowed: what is stored is left as it is and the previously published governance site
 * stays served, the same rule every other import of this service follows.
 */
public class GovernanceUnavailableException extends RuntimeException {

    public GovernanceUnavailableException(String message) {
        super(message);
    }

    public GovernanceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
