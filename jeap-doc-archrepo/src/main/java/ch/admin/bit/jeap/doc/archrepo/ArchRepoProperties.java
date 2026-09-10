package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.upstream.UpstreamClientSettings;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which architecture repository the model of an environment is read from.
 * <p>
 * <b>The map is instance-wide and keyed by environment id, not a property of each site's environment.</b> Two
 * sites with a {@code prod} environment mean the same stage of the same landscape, so configuring the upstream
 * per site would be the same URL written twice, in two places that can disagree.
 * <p>
 * An environment no entry names simply has no model-derived content - a legitimate configuration, and what an
 * instance that only serves uploaded documentation looks like.
 */
@Data
@ConfigurationProperties("jeap.doc.archrepo")
public class ArchRepoProperties {

    /**
     * The architecture repositories, keyed by the id of the environment whose model they hold.
     */
    private Map<String, Environment> environments = new LinkedHashMap<>();

    /**
     * What the client does when the architecture repository is slow. The same settings every outbound client
     * of this service has, under this upstream's own prefix - see {@code UpstreamClientSettings}.
     */
    private UpstreamClientSettings client = new UpstreamClientSettings();

    @Data
    public static class Environment {

        /**
         * Where the architecture repository is, without a path below its context path - the <b>internal</b>
         * host on a platform that gives a service two of them, because this is a service calling a service and
         * not a browser following a link.
         */
        private String url;

        /**
         * The Spring Security OAuth2 client registration the token is obtained with. The client needs the role
         * {@code <system-name>_@architecture-model_#read} on the architecture repository's authorization
         * server - which is not this service's own.
         */
        private String clientRegistration;
    }

}
