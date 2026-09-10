package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.upstream.UpstreamClientSettings;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether this instance reads reactions at all, and which reaction observer serves an environment.
 * <p>
 * Shaped like the architecture repository's properties, and keyed by environment id for the same reason: one
 * doc service reads the architecture repository of every stage, so it reads the reaction observer of every
 * stage too, and every graph it stores has to say which one it came from.
 * <p>
 * <b>The flag earns its place beside the map.</b> An empty map would already mean <i>no reactions</i>, but then
 * a platform that has no reaction observer and a platform whose property path has a typo would look exactly
 * the same and both import nothing in silence. With the flag, {@code enabled: true} and an empty map is a
 * startup failure. The default is off, because that is the safe end for a library: an instance that upgrades
 * gets no new outbound traffic to a service its platform may not run.
 */
@Data
@ConfigurationProperties("jeap.doc.reactions")
public class ReactionObserverProperties {

    /**
     * Whether the reactions are imported. Off means no import step is registered, nothing is called and no
     * state row is written.
     */
    private boolean enabled = false;

    /**
     * The reaction observers, keyed by the id of the environment whose reactions they observe. An environment
     * no entry names has no reactions, even with the flag on.
     */
    private Map<String, Environment> environments = new LinkedHashMap<>();

    /**
     * The largest graph this service stores. A body over it is not read whole and not stored - it is one
     * graph left behind rather than a heap this service chose to fill.
     */
    private DataSize maxGraphSize = DataSize.ofMegabytes(8);

    /**
     * What the client does when the observer is slow. <b>Its own block</b>, defaulting to the same values the
     * architecture repository client uses - reading the other upstream's block instead would mean tuning the
     * observer's read timeout under {@code jeap.doc.archrepo}, which is a name that lies about what it does.
     */
    private UpstreamClientSettings client = new UpstreamClientSettings();

    @Data
    public static class Environment {

        /**
         * Where the reaction observer of that stage is, without a path below its context path - the
         * <b>internal</b> host, because this is a service calling a service.
         */
        private String url;

        /**
         * The Spring Security OAuth2 client registration the token is obtained with. The client needs the role
         * {@code <system-name>_@reactions_#read} on the authorization server of <b>that stage</b>, which is
         * not this service's own.
         * <p>
         * There is no other way to authenticate. The reaction observer offers HTTP Basic as well, and this
         * service does not use it: a second credential model to operate is worth more than it saves, and the
         * release of the observer that serves the replication indexes requires a resource server anyway.
         */
        private String clientRegistration;
    }
}
