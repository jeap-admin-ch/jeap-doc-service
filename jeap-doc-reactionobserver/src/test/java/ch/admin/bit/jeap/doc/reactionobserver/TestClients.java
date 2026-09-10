package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.upstream.UpstreamClientSettings;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Consumer;

/**
 * Clients pointed at a local server, with the token left out.
 * <p>
 * <b>These tests do not cover the client-credentials flow</b>, and they are not meant to: everything above the
 * token - the routes, the binding, the mapping, the status handling - is what breaks, and it breaks the same
 * way whether a bearer header is present or not. The flow itself is covered where it can be: by the reaction
 * observer's own security tests on one side, and by the smoke test after a deployment on the other.
 */
final class TestClients {

    private TestClients() {
    }

    /** The reaction observer clients of one environment, with the client settings a test wants. */
    static ReactionObserverClients of(String environment, String url,
                                      Consumer<UpstreamClientSettings> settings) {
        ReactionObserverProperties properties = new ReactionObserverProperties();
        properties.setEnabled(true);
        ReactionObserverProperties.Environment observer = new ReactionObserverProperties.Environment();
        observer.setUrl(url);
        observer.setClientRegistration("test");
        properties.getEnvironments().put(environment, observer);
        settings.accept(properties.getClient());
        return new ReactionObserverClients(properties, withoutAToken(), JsonMapper.builder().build());
    }

    private static JeapOAuth2RestClientBuilderFactory withoutAToken() {
        return new JeapOAuth2RestClientBuilderFactory() {

            @Override
            public RestClient.Builder createForClientRegistryId(String clientRegistryId) {
                return RestClient.builder();
            }

            @Override
            public RestClient.Builder createForClientRegistryIdPreferringTokenFromIncomingRequest(String id) {
                return RestClient.builder();
            }

            @Override
            public RestClient.Builder createForTokenFromIncomingRequest() {
                return RestClient.builder();
            }
        };
    }
}
