package ch.admin.bit.jeap.doc.domain.custom;

/**
 * Where a system or component that documentation was uploaded for stands in its deployment.
 * <p>
 * <b>What decides when documentation is removed.</b> An upload records a subject it has never seen as
 * {@link State#AWAITING_DEPLOYMENT} - documentation may well be published before what it documents is deployed
 * anywhere. The architecture import marks it {@link State#DEPLOYED} once it appears in the model, and
 * {@link State#UNDEPLOYED} once it has left the model again, which is when its documentation is removed.
 * <p>
 * A library has no deployment of its own: it is never in the architecture model, and its documentation is
 * never removed by a deployment - not even when its system leaves the model.
 *
 * @param site      the site the subject is documented on
 * @param system    the system's slug
 * @param component the component's slug, null for the system itself
 * @param state     where it stands
 */
public record SubjectDeployment(String site, String system, String component, State state) {

    public SubjectDeployment {
        if (site == null || system == null || state == null) {
            throw new IllegalArgumentException("A subject deployment names a site, a system and a state.");
        }
    }

    public boolean isSystem() {
        return component == null;
    }

    public enum State {
        /**
         * Documentation was uploaded, and the subject has not been seen in the architecture model yet. Nothing
         * is removed.
         */
        AWAITING_DEPLOYMENT,
        /**
         * The subject is in the architecture model. Nothing is removed, and every upload is added to what is
         * there.
         */
        DEPLOYED,
        /**
         * The subject was deployed and has left the architecture model. All of its documentation was removed. An
         * upload makes it {@link #AWAITING_DEPLOYMENT} again, and appearing in the model makes it {@link #DEPLOYED}.
         */
        UNDEPLOYED
    }
}
