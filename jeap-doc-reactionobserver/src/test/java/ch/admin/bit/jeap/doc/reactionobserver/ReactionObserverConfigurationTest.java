package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checks that turn a misconfigured instance from <i>a chapter is missing from the site</i> into <i>the
 * service does not start</i>.
 */
class ReactionObserverConfigurationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReactionObserverConfiguration configuration = new ReactionObserverConfiguration();
    private final DocumentationSites sites = new DocumentationSites(new SiteProperties());

    /**
     * <b>Why the flag exists beside the map.</b> Without it, a platform that runs no reaction observer and a
     * platform whose property path has a typo look exactly the same, and both import nothing in silence.
     */
    @Test
    void theReactionsSwitchedOnWithNoEnvironment_failsTheStartup() {
        ReactionObserverProperties properties = new ReactionObserverProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> clientsFor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.reactions.enabled is true")
                .hasMessageContaining("names no environment");
    }

    @Test
    void anEnvironmentNoSiteDeclares_isATypoAndFailsTheStartup() {
        assertThatThrownBy(() -> clientsFor("nowhere", "https://observer", "doc-client"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nowhere")
                .hasMessageContaining("dev");
    }

    @ParameterizedTest
    @ValueSource(strings = {"observer", "/observer", "observer:8080/observer", "https://"})
    void anEnvironmentWhoseUrlIsNotAbsolute_failsTheStartup(String url) {
        assertThatThrownBy(() -> clientsFor("dev", url, "doc-client"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url");
    }

    /**
     * The reaction observer is read with a client-credentials token and with nothing else: it offers HTTP
     * Basic as well, and this service does not use it. So an environment without a registration cannot be
     * read at all, and says so now rather than an hour later.
     */
    @Test
    void anEnvironmentWithoutAClientRegistration_failsTheStartup() {
        assertThatThrownBy(() -> clientsFor("dev", "https://observer", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-registration")
                .hasMessageContaining("_@reactions_#read");
    }

    @Test
    void aClientRegistrationThatDoesNotExist_failsTheStartup() {
        assertThatThrownBy(() -> clientsFor("dev", "https://observer", "not-configured"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-configured")
                .hasMessageContaining("spring.security.oauth2.client.registration");
    }

    /**
     * The reactions are steps of the architecture import, which runs the environments the architecture
     * repository is configured for - so an environment only the reactions name is never visited, and a runtime
     * view has no model to be drawn onto anyway.
     */
    @Test
    void anEnvironmentWithoutAnArchitectureRepository_failsTheStartup() {
        ReactionObserverProperties properties = properties("dev", "https://observer", "doc-client");

        assertThatThrownBy(() -> configuration.reactionObserverClients(properties, sites, JSON,
                provider(builders()), provider(registrations()), provider(models())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.archrepo.environments");
    }

    /** The other way round is a landscape: a stage may have an architecture repository and no observer. */
    @Test
    void anArchitectureRepositoryWithoutAReactionObserver_isAServiceThatStarts() {
        ReactionObserverProperties properties = new ReactionObserverProperties();

        assertThatCode(() -> configuration.reactionObserverClients(properties, sites, JSON,
                provider(builders()), provider(registrations()), provider(models("dev"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aConfiguredEnvironment_startsAndIsReadable() {
        ReactionObserverClients clients = clientsFor("dev", "https://observer/context", "doc-client");

        assertThat(clients.environments()).containsExactly("dev");
        assertThat(clients.urlOf("dev")).contains("https://observer/context");
    }

    /**
     * A platform that runs no reaction observer is the default, and it is a service that starts and never
     * calls out.
     */
    @Test
    void theReactionsSwitchedOff_isAServiceThatStarts() {
        ReactionObserverClients clients = clientsFor(new ReactionObserverProperties());

        assertThat(clients.environments()).isEmpty();
    }

    /**
     * <b>Off means off, whatever the map says.</b> A configuration left behind by a rollback must not have
     * this instance calling an observer it was told not to read.
     */
    @Test
    void theReactionsSwitchedOffWithAnEnvironmentConfigured_thenNoClientIsBuilt() {
        ReactionObserverProperties properties = properties("dev", "https://observer", "doc-client");
        properties.setEnabled(false);

        ReactionObserverClients clients = clientsFor(properties);

        assertThat(clients.environments()).isEmpty();
        assertThat(clients.of("dev")).isEmpty();
    }

    /**
     * An instance whose token comes from somewhere else than a Spring OAuth2 client registry - which is what
     * an integration test looks like - has nothing to resolve the name against, and must still start.
     */
    @Test
    void whenThereIsNoClientRegistryAtAll_thenTheNameIsNotChecked() {
        ReactionObserverProperties properties = properties("dev", "https://observer", "resolved-elsewhere");

        assertThatCode(() -> configuration.reactionObserverClients(properties, sites, JSON,
                provider(builders()), provider(null), provider(models("dev"))))
                .doesNotThrowAnyException();
    }

    /**
     * The observer's own paths carry its context path, and so does the configured URL. Appending one to the
     * other answers 404 on every graph.
     */
    @Test
    void aGraphUrlIsResolvedAgainstTheOriginRatherThanAppended() {
        ReactionObserverClients clients = clientsFor("dev", "https://observer/reaction-observer",
                "doc-client");

        assertThat(clients.resolve("dev", "/reaction-observer/api/graphs/systems/orders"))
                .contains(java.net.URI.create("https://observer/reaction-observer/api/graphs/systems/orders"));
    }

    private ReactionObserverClients clientsFor(String environment, String url, String registration) {
        return clientsFor(properties(environment, url, registration));
    }

    private ReactionObserverClients clientsFor(ReactionObserverProperties properties) {
        return configuration.reactionObserverClients(properties, sites, JSON, provider(builders()),
                provider(registrations()), provider(models("dev")));
    }

    private static ReactionObserverProperties properties(String environment, String url,
                                                         String registration) {
        ReactionObserverProperties properties = new ReactionObserverProperties();
        properties.setEnabled(true);
        ReactionObserverProperties.Environment observer = new ReactionObserverProperties.Environment();
        observer.setUrl(url);
        observer.setClientRegistration(registration);
        properties.getEnvironments().put(environment, observer);
        return properties;
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        return new ObjectProvider<>() {

            @Override
            public T getObject() {
                if (bean == null) {
                    throw new NoSuchBeanDefinitionException("none");
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

    /** An architecture repository configured for the given environments, and asked about nothing else. */
    private static ArchitectureModelUpstream models(String... environments) {
        return new ArchitectureModelUpstream() {

            @Override
            public Set<String> environments() {
                return Set.of(environments);
            }

            @Override
            public Optional<String> urlOf(String environment) {
                return Optional.empty();
            }

            @Override
            public List<String> systemNames(String environment) {
                return List.of();
            }

            @Override
            public Optional<SystemTopology> topology(String environment, String system) {
                return Optional.empty();
            }

            @Override
            public Optional<List<DocumentedMessage>> messages(String environment, String system) {
                return Optional.empty();
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
