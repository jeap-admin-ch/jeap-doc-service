package ch.admin.bit.jeap.doc.domain.port;

/**
 * The site generator ran past {@code jeap.doc.build.timeout} and its process tree was destroyed.
 * <p>
 * Its own type so that a build that overran its budget is <b>counted apart from one that broke</b>. The two look
 * the same on the row - both leave the build failed - but they are not the same defect and they are not fixed
 * the same way: a generator that exits with an error is wrong about something, while a generator that does not
 * finish is right about everything and too big for the time it was given. Folded together they also spoil each
 * other's duration, because every timeout records the same one.
 */
public class SiteBuildTimeoutException extends SiteBuildException {

    public SiteBuildTimeoutException(String message) {
        super(message);
    }
}
