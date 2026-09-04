package ch.admin.bit.jeap.doc.archrepo;

import lombok.Getter;

/**
 * A request to the architecture repository that did not answer with what was asked for.
 * <p>
 * It carries the status and, for an {@code application/problem+json} answer, its {@code type}. A caller can
 * then decide whether to skip one system or give up on the run.
 */
@Getter
public class ArchRepoException extends RuntimeException {

    private final int status;
    private final String problemType;

    public ArchRepoException(String message, int status, String problemType, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.problemType = problemType;
    }

    /** Whether the thing asked for is not there. Usually a system deleted between two requests. */
    public boolean isNotFound() {
        return status == 404;
    }

    /** Whether this instance is not allowed to read the model, which is a deployment error. */
    public boolean isUnauthorized() {
        return status == 401 || status == 403;
    }

    /**
     * Whether asking again could plausibly answer differently: the upstream is failing or is shedding load.
     * <p>
     * A subclass rather than a flag, because the retry policy selects on the exception class. A 401 is not one
     * of these - the token or the role is wrong and will be wrong again in half a second - and neither is a
     * 404, which both callers of this adapter already have an answer for.
     */
    public static boolean isWorthRetrying(int status) {
        return status >= 500 || status == 429;
    }

    /**
     * A failure the client retries. Thrown for {@code 5xx} and {@code 429}.
     */
    public static class Retryable extends ArchRepoException {

        public Retryable(String message, int status, String problemType, Throwable cause) {
            super(message, status, problemType, cause);
        }
    }
}
