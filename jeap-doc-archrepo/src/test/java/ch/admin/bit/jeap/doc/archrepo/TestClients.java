package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Consumer;

/**
 * Clients pointed at a local server, with the token left out.
 * <p>
 * <b>These tests do not cover the client-credentials flow</b>, and they are not meant to: everything above the
 * token - the routes, the binding, the mapping, the status handling - is what breaks, and it breaks the same
 * way whether a bearer header is present or not. The flow itself is covered where it can be: by the
 * architecture repository's own security tests on one side, and by the smoke test after a deployment on the
 * other.
 */
final class TestClients {

    private TestClients() {
    }

    static ArchRepoClients of(String environment, String url) {
        return of(environment, url, client -> {
        });
    }

    /** The same, with the client settings a test wants - a read timeout it does not have to wait out. */
    static ArchRepoClients of(String environment, String url, Consumer<ArchRepoProperties.Client> settings) {
        ArchRepoProperties properties = new ArchRepoProperties();
        ArchRepoProperties.Environment upstream = new ArchRepoProperties.Environment();
        upstream.setUrl(url);
        upstream.setClientRegistration("test");
        properties.getEnvironments().put(environment, upstream);
        settings.accept(properties.getClient());
        return new ArchRepoClients(properties, withoutAToken(), JsonMapper.builder().build());
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
