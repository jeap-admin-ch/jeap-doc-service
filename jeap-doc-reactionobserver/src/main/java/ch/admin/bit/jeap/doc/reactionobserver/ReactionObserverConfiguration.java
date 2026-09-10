package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import ch.admin.bit.jeap.doc.upstream.UpstreamChecks;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.TreeSet;

/**
 * Wires the client of the reaction observer, and says which environments it will read.
 * <p>
 * The upstream is not called while the service starts. An observer may be deploying, and an instance that will
 * not boot because a neighbour is restarting cannot serve the documentation it already has.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ReactionObserverProperties.class)
public class ReactionObserverConfiguration {

    /**
     * The clients, one per environment the reaction map names.
     * <p>
     * Built whatever {@code jeap.doc.reactions.enabled} says, and empty when it is off - the flag is what
     * decides whether an <b>import step</b> is registered, which is the thing that would call out. A port with
     * no adapter behind it would instead be a hole in the wiring that every context has to know about, and an
     * adapter with nothing configured answers "no reactions here" all by itself.
     * <p>
     * {@code getIfUnique} rather than {@code getIfAvailable} for the OAuth2 builders, as the architecture
     * repository's configuration decided for the same situation: {@code getIfAvailable} throws where a context
     * holds several candidates.
     */
    @Bean
    ReactionObserverClients reactionObserverClients(ReactionObserverProperties properties,
                                                    DocumentationSites sites, JsonMapper json,
                                                    ObjectProvider<JeapOAuth2RestClientBuilderFactory>
                                                            clientBuilders,
                                                    ObjectProvider<ClientRegistrationRepository>
                                                            clientRegistrations,
                                                    ObjectProvider<ArchitectureModelUpstream> models) {
        check(properties, sites, clientRegistrations.getIfUnique(), models.getIfUnique());
        report(properties, sites);
        // The OAuth2 builders are only needed where a client is actually built. An instance that reads no
        // reactions configures no client registration and must still start, so they are asked for rather than
        // injected.
        boolean callsAnything = properties.isEnabled() && !properties.getEnvironments().isEmpty();
        return new ReactionObserverClients(properties, callsAnything ? clientBuilders.getObject() : null,
                json);
    }

    @Bean
    ReactionGraphUpstream reactionGraphUpstream(ReactionObserverClients clients,
                                                ReactionObserverProperties properties) {
        return new ReactionObserverUpstream(clients, properties);
    }

    /**
     * Reading a stored graph needs no client and no configuration. It only needs to know the shape of this
     * upstream's payload, which is what this module is for.
     */
    @Bean
    ReactionGraphContent reactionGraphContent(JsonMapper json) {
        return new ReactionObserverGraphContent(json);
    }

    /**
     * What a deployment can get wrong and nobody would notice until a chapter was missing from a site.
     * <p>
     * <b>The flag on with an empty map is a failure</b>, and that is the whole reason the flag exists beside
     * the map: without it, a platform that runs no reaction observer and a platform whose property path has a
     * typo look exactly the same, and both import nothing in silence.
     * <p>
     * <b>And an environment without an architecture repository is one too.</b> The reactions are imported as
     * steps of that import, which runs the environments the architecture repository is configured for, so an
     * environment only this map names is never visited - and it could draw nothing anyway, since a runtime
     * view is written onto the pages of a model. The reverse is a landscape: a stage may have an architecture
     * repository and no reaction observer, and most do.
     * <p>
     * The URL and the registration are checked whatever the flag says. Configuration is rolled out ahead of
     * the release that reads it, and an entry that cannot be read once the flag is turned on is worth hearing
     * about while it is still being deployed.
     *
     * @param models the architecture repositories, or null in a context that wires none - which is a test and
     *               not an instance, and there is then nothing to check the environments against
     */
    private static void check(ReactionObserverProperties properties, DocumentationSites sites,
                              ClientRegistrationRepository clientRegistrations,
                              ArchitectureModelUpstream models) {
        if (properties.isEnabled() && properties.getEnvironments().isEmpty()) {
            throw new IllegalStateException(
                    "jeap.doc.reactions.enabled is true and jeap.doc.reactions.environments names no "
                    + "environment, so nothing would be imported. Configure "
                    + "jeap.doc.reactions.environments.<environment>.url and .client-registration, or switch "
                    + "the reactions off.");
        }
        Set<String> declared = declaredEnvironments(sites);
        Set<String> withAModel = models == null ? null : new TreeSet<>(models.environments());
        properties.getEnvironments().forEach((environment, observer) -> {
            if (!declared.contains(environment)) {
                throw new IllegalStateException((
                        "jeap.doc.reactions.environments.%s names an environment no site declares. The "
                        + "environments that exist are %s - reactions nothing would ever draw is what this "
                        + "would otherwise be, and it is a typo and nothing else.")
                        .formatted(environment, declared));
            }
            if (withAModel != null && !withAModel.contains(environment)) {
                throw new IllegalStateException((
                        "jeap.doc.reactions.environments.%s names an environment jeap.doc.archrepo.environments "
                        + "does not, and the reactions are imported as steps of that import - so nothing would "
                        + "ever read them. The environments with an architecture repository are %s. The other "
                        + "way round is a landscape and not an error: a stage may have an architecture "
                        + "repository and no reaction observer.")
                        .formatted(environment, withAModel));
            }
            UpstreamChecks.requireAnAbsoluteUrl(
                    "jeap.doc.reactions.environments.%s.url".formatted(environment), observer.getUrl());
            UpstreamChecks.requireAClientRegistration(
                    "jeap.doc.reactions.environments.%s.client-registration".formatted(environment),
                    observer.getClientRegistration(), "<system-name>_@reactions_#read on that stage's "
                                                      + "authorization server", clientRegistrations);
        });
    }

    private static Set<String> declaredEnvironments(DocumentationSites sites) {
        Set<String> declared = new TreeSet<>();
        for (Site site : sites.all()) {
            site.environments().stream().map(SiteEnvironment::id).forEach(declared::add);
        }
        return declared;
    }

    private static void report(ReactionObserverProperties properties, DocumentationSites sites) {
        if (!properties.isEnabled()) {
            log.info("The reactions are not imported (jeap.doc.reactions.enabled is false), so no runtime "
                     + "views are generated.");
            return;
        }
        properties.getEnvironments().forEach((environment, observer) -> log.info(
                "The reactions of the environment {} are read from {} as the client {}.",
                environment, observer.getUrl(), observer.getClientRegistration()));
        Set<String> without = new TreeSet<>(declaredEnvironments(sites));
        without.removeAll(properties.getEnvironments().keySet());
        if (!without.isEmpty()) {
            // Said rather than checked: an environment whose stage runs no observer is a landscape, and an
            // environment left out by a typo looks the same from here. It is also the typo one would most
            // like to hear about.
            log.info("No reaction observer is configured for {}, so those trees carry no runtime views.",
                    without);
        }
    }
}
