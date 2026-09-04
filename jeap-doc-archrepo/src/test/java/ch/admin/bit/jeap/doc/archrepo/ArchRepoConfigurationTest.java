package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checks that turn a misconfigured instance from <i>every build of this environment fails an hour from
 * now</i> into <i>the service does not start</i>.
 */
class ArchRepoConfigurationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArchRepoConfiguration configuration = new ArchRepoConfiguration();
    private final DocumentationSites sites = new DocumentationSites(new SiteProperties());

    @Test
    void anEnvironmentNoSiteDeclares_isATypoAndFailsTheStartup() {
        assertThatThrownBy(() -> clientsFor("nowhere", "https://archrepo", "doc-client"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nowhere")
                .hasMessageContaining("dev");
    }

    @Test
    void anEnvironmentWithoutAUrl_failsTheStartup() {
        assertThatThrownBy(() -> clientsFor("dev", "  ", "doc-client"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url");
    }

    /**
     * Every content URL of an artifact is resolved against the <b>origin</b> of the upstream, and a URL with no
     * scheme and host has no origin to resolve against. Left to the first import, this is an environment whose
     * artifacts all silently fail to fetch an hour after the deployment.
     */
    @ParameterizedTest
    @ValueSource(strings = {"archrepo", "/archrepo", "archrepo:8080/archrepo", "https://"})
    void anEnvironmentWhoseUrlIsNotAbsolute_failsTheStartup(String url) {
        assertThatThrownBy(() -> clientsFor("dev", url, "doc-client"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url");
    }

    @Test
    void anEnvironmentWhoseUrlIsNotAUrlAtAll_failsTheStartup() {
        assertThatThrownBy(() -> clientsFor("dev", "https://arch repo", "doc-client"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("which is not a URL");
    }

    @Test
    void anEnvironmentWithoutAClientRegistration_failsTheStartup() {
        assertThatThrownBy(() -> clientsFor("dev", "https://archrepo", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-registration")
                .hasMessageContaining("architecture-model");
    }

    /**
     * A registration that is named but does not exist is a {@code 500} on the first build, an hour later.
     */
    @Test
    void aClientRegistrationThatDoesNotExist_failsTheStartup() {
        assertThatThrownBy(() -> clientsFor("dev", "https://archrepo", "not-configured"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-configured")
                .hasMessageContaining("spring.security.oauth2.client.registration");
    }

    @Test
    void aConfiguredEnvironment_startsAndIsReadable() {
        ArchRepoClients clients = clientsFor("dev", "https://archrepo/context", "doc-client");

        assertThat(clients.environments()).containsExactly("dev");
        assertThat(clients.urlOf("dev")).contains("https://archrepo/context");
    }

    /**
     * An instance that only serves uploaded documentation is a legitimate instance.
     */
    @Test
    void noArchitectureRepositoryAtAll_isAServiceThatStarts() {
        assertThatCode(() -> configuration.archRepoClients(
                new ArchRepoProperties(), sites, JSON, provider(null), provider(null)))
                .doesNotThrowAnyException();
    }

    /**
     * An instance whose token comes from somewhere else than a Spring OAuth2 client registry - which is what an
     * integration test looks like - has nothing to resolve the name against, and must still start.
     */
    @Test
    void whenThereIsNoClientRegistryAtAll_thenTheNameIsNotChecked() {
        ArchRepoProperties properties = new ArchRepoProperties();
        ArchRepoProperties.Environment upstream = new ArchRepoProperties.Environment();
        upstream.setUrl("https://archrepo");
        upstream.setClientRegistration("resolved-elsewhere");
        properties.getEnvironments().put("dev", upstream);

        assertThatCode(() -> configuration.archRepoClients(properties, sites, JSON, provider(builders()),
                provider(null))).doesNotThrowAnyException();
    }

    /**
     * The architecture repository builds its content URLs carrying its own context path, and the configured
     * upstream carries that same context path. Appending one to the other answers 404 on every artifact.
     */
    @Test
    void aContentUrlIsResolvedAgainstTheOriginRatherThanAppended() {
        ArchRepoClients clients = clientsFor("dev", "https://archrepo/jme-archrepo-service", "doc-client");

        assertThat(clients.resolve("dev", "/jme-archrepo-service/docs-api/systems/orders/x/openapi"))
                .contains(java.net.URI.create("https://archrepo/jme-archrepo-service/docs-api/systems/orders/x/openapi"));
    }

    private ArchRepoClients clientsFor(String environment, String url, String registration) {
        ArchRepoProperties properties = new ArchRepoProperties();
        ArchRepoProperties.Environment upstream = new ArchRepoProperties.Environment();
        upstream.setUrl(url);
        upstream.setClientRegistration(registration);
        properties.getEnvironments().put(environment, upstream);
        return configuration.archRepoClients(properties, sites, JSON, provider(builders()),
                provider(registrations()));
    }

    /**
     * An instance that configures no OAuth2 client at all - which is what an instance reading no architecture
     * model looks like.
     */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
        return new org.springframework.beans.factory.ObjectProvider<>() {

            @Override
            public T getObject() {
                if (bean == null) {
                    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("none");
                }
                return bean;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }
        };
    }

    private static ClientRegistrationRepository registrations() {
        return id -> "doc-client".equals(id)
                ? ClientRegistration.withRegistrationId("doc-client")
                .clientId("doc-client")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("https://auth.example.com/oauth2/token")
                .build()
                : null;
    }

    private static JeapOAuth2RestClientBuilderFactory builders() {
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
