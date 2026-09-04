package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wires the client of the architecture repository, and refuses to start when it is configured wrongly.
 * <p>
 * The upstream is not called while the service starts. An architecture repository may be deploying, and an
 * instance that will not boot because a neighbour is restarting cannot serve the documentation it already has.
 * What is checked here is what a deployment can get wrong and nobody would notice until a page was missing.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ArchRepoProperties.class)
public class ArchRepoConfiguration {

    /**
     * The OAuth2 client is only needed when there is something to call. An instance that reads no architecture
     * model configures no client registration and must still start, so both are asked for rather than
     * injected.
     * <p>
     * {@code getIfUnique} rather than {@code getIfAvailable}: the check wants the registrations of the
     * instance if there is one unambiguous set of them, and no more than that. {@code getIfAvailable} throws
     * where a context holds several candidates, which would turn a check that only ever produces a better
     * error message into the thing that fails the startup.
     */
    @Bean
    ArchRepoClients archRepoClients(ArchRepoProperties properties, DocumentationSites sites, JsonMapper json,
                                    ObjectProvider<JeapOAuth2RestClientBuilderFactory> clientBuilders,
                                    ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        check(properties, sites, clientRegistrations.getIfUnique());
        report(properties, sites);
        return properties.getEnvironments().isEmpty()
                ? new ArchRepoClients(properties, null, json)
                : new ArchRepoClients(properties, clientBuilders.getObject(), json);
    }

    @Bean
    ArchitectureModelUpstream architectureModelUpstream(ArchRepoClients clients) {
        return new ArchRepoModelUpstream(clients);
    }

    @Bean
    ArchitectureArtifactUpstream architectureArtifactUpstream(ArchRepoClients clients,
                                                              ArchitectureImportProperties properties) {
        return new ArchRepoArtifactUpstream(clients, properties);
    }

    @Bean
    MessageSchemaUpstream messageSchemaUpstream(ArchRepoClients clients,
                                               ArchitectureImportProperties importProperties) {
        return new ArchRepoMessageSchemaUpstream(clients, importProperties);
    }

    private static void check(ArchRepoProperties properties, DocumentationSites sites,
                              ClientRegistrationRepository clientRegistrations) {
        Set<String> declared = declaredEnvironments(sites);
        properties.getEnvironments().forEach((environment, upstream) -> {
            if (!declared.contains(environment)) {
                throw new IllegalStateException((
                        "jeap.doc.archrepo.environments.%s names an environment no site declares. The "
                        + "environments that exist are %s - a tree that is never generated is what this would "
                        + "otherwise be, and it is a typo and nothing else.")
                        .formatted(environment, declared));
            }
            if (upstream.getUrl() == null || upstream.getUrl().isBlank()) {
                throw new IllegalStateException(
                        "jeap.doc.archrepo.environments.%s.url is not configured.".formatted(environment));
            }
            requireAnAbsoluteUrl(environment, upstream.getUrl());
            if (upstream.getClientRegistration() == null || upstream.getClientRegistration().isBlank()) {
                throw new IllegalStateException((
                        "jeap.doc.archrepo.environments.%s.client-registration is not configured. The doc "
                        + "service reads the architecture model with a client-credentials token, and the "
                        + "client needs the role <system-name>_@architecture-model_#read.")
                        .formatted(environment));
            }
            if (clientRegistrations == null) {
                // No registry means the token comes from somewhere else, which is what a test looks like.
                // There is nothing to check the name against.
                log.warn("jeap.doc.archrepo.environments.{} names the client registration '{}', and this "
                         + "instance has no OAuth2 client registry to resolve it against. Unless the token "
                         + "comes from elsewhere, configure it under "
                         + "spring.security.oauth2.client.registration.",
                        environment, upstream.getClientRegistration());
                return;
            }
            if (clientRegistrations.findByRegistrationId(upstream.getClientRegistration()) == null) {
                throw new IllegalStateException((
                        "jeap.doc.archrepo.environments.%s.client-registration is '%s', and no such client "
                        + "registration is configured under spring.security.oauth2.client.registration. "
                        + "Without it every build of this environment would fail an hour from now.")
                        .formatted(environment, upstream.getClientRegistration()));
            }
        });
    }

    /**
     * The upstream has to be an absolute URL, because every content URL of an artifact is resolved against its
     * origin. Without a scheme there is no origin to resolve against, and the first artifact of the first
     * import would be the one to find out.
     */
    private static void requireAnAbsoluteUrl(String environment, String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(("jeap.doc.archrepo.environments.%s.url is '%s', which is not a "
                                             + "URL.").formatted(environment, url), e);
        }
        if (parsed.getScheme() == null || parsed.getHost() == null) {
            throw new IllegalStateException(("jeap.doc.archrepo.environments.%s.url is '%s', which names no "
                                             + "scheme and host. It has to be absolute, because the content "
                                             + "URL of every artifact is resolved against its origin.")
                    .formatted(environment, url));
        }
    }

    private static void report(ArchRepoProperties properties, DocumentationSites sites) {
        if (properties.getEnvironments().isEmpty()) {
            log.info("No architecture repository is configured, so no documentation is generated from the "
                     + "architecture model. Configure jeap.doc.archrepo.environments.<environment>.");
            return;
        }
        properties.getEnvironments().forEach((environment, upstream) -> log.info(
                "The architecture model of the environment {} is read from {} as the client {}.",
                environment, upstream.getUrl(), upstream.getClientRegistration()));
        Set<String> without = new TreeSet<>(declaredEnvironments(sites));
        without.removeAll(properties.getEnvironments().keySet());
        if (!without.isEmpty()) {
            log.info("No architecture repository is configured for {}, so those trees carry no model-derived "
                     + "documentation.", without);
        }
    }

    private static Set<String> declaredEnvironments(DocumentationSites sites) {
        Set<String> declared = new TreeSet<>();
        for (Site site : sites.all()) {
            site.environments().stream().map(SiteEnvironment::id).forEach(declared::add);
        }
        return declared;
    }
}
