package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceContent;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
import ch.admin.bit.jeap.doc.upstream.UpstreamChecks;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Wires the client of the Governance Service, and refuses to start when it is configured wrongly.
 * <p>
 * The upstream is not called while the service starts. A Governance Service may be deploying, and an instance
 * that will not boot because a neighbour is restarting cannot serve the governance site it already has.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(GovernanceProperties.class)
public class GovernanceConfiguration {

    /**
     * The OAuth2 client is only needed when there is something to call. An instance with no governance site
     * configures no client registration and must still start, so both are asked for rather than injected -
     * see {@code ArchRepoConfiguration} for the same reasoning.
     */
    @Bean
    GovernanceClients governanceClients(GovernanceProperties properties, JsonMapper json,
                                        ObjectProvider<JeapOAuth2RestClientBuilderFactory> clientBuilders,
                                        ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        check(properties, clientRegistrations.getIfUnique());
        report(properties);
        return properties.getUrl() == null
                ? new GovernanceClients(properties, null, json)
                : new GovernanceClients(properties, clientBuilders.getObject(), json);
    }

    @Bean
    GovernanceUpstream governanceUpstream(GovernanceClients clients, GovernanceProperties properties,
                                          Clock clock) {
        return new GovernanceUpstreamAdapter(clients, properties, clock);
    }

    /**
     * Reading a stored resource needs no client and no configuration. It only needs to know the shapes of
     * this upstream's payloads, which is what this module is for - see {@code ArchRepoConfiguration}'s {@code
     * architectureArtifactContent} for the same split.
     */
    @Bean
    GovernanceResourceContent governanceResourceContent(JsonMapper json) {
        return new GovernanceResourceContentAdapter(json);
    }

    private static void check(GovernanceProperties properties, ClientRegistrationRepository clientRegistrations) {
        if (properties.getUrl() == null) {
            // Not configured is a legitimate instance: one that publishes no governance site.
            return;
        }
        UpstreamChecks.requireAnAbsoluteUrl("jeap.doc.governance.url", properties.getUrl());
        UpstreamChecks.requireAClientRegistration("jeap.doc.governance.client-registration",
                properties.getClientRegistration(), "<system-name>_@governance-documentation_#read",
                clientRegistrations);
    }

    private static void report(GovernanceProperties properties) {
        if (properties.getUrl() == null) {
            log.info("No Governance Service is configured, so no governance documentation is generated. "
                     + "Configure jeap.doc.governance.url.");
            return;
        }
        log.info("The Governance Service is read from {} as the client {}.", properties.getUrl(),
                properties.getClientRegistration());
    }
}
