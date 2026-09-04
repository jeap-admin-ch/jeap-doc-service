package ch.admin.bit.jeap.doc.archrepo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
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
     * What the client does when the architecture repository is slow.
     */
    private Client client = new Client();

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

    @Data
    public static class Client {

        /**
         * How long the client waits for the connection.
         */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /**
         * How long the client waits for one response. The budget of a whole import is its deadline.
         */
        private Duration readTimeout = Duration.ofSeconds(30);

        /**
         * How often a failed request is tried again, so two means three attempts in all. Only a connection
         * failure, a read timeout, a {@code 5xx} or a {@code 429} is retried.
         */
        private int retries = 2;

        /**
         * How long to wait before the first retry. Doubled for each further one.
         */
        private Duration retryDelay = Duration.ofMillis(500);

        /**
         * How much the delay is varied, so that instances whose schedules fire together do not retry in
         * lockstep.
         */
        private Duration retryJitter = Duration.ofMillis(250);

        /**
         * The longest a retry waits, however often the delay has been doubled.
         */
        private Duration maxRetryDelay = Duration.ofSeconds(2);
    }
}
