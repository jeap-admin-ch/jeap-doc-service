package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * A client pointed at a local server, with the token left out.
 * <p>
 * <b>These tests do not cover the client-credentials flow</b>, and they are not meant to - see {@code
 * ArchRepoClients}' own {@code TestClients} for the same reasoning.
 */
final class TestClients {

    private TestClients() {
    }

    static GovernanceClients of(String url) {
        GovernanceProperties properties = new GovernanceProperties();
        properties.setUrl(url);
        properties.setClientRegistration("test");
        return new GovernanceClients(properties, withoutAToken(), JsonMapper.builder().build());
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
