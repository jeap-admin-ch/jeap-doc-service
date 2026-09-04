package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchemaReference;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.OpenApiReference;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * The architecture model over the {@code /docs-api} of the architecture repository.
 * <p>
 * This class knows HTTP and the payloads, and nothing else. Which names may be documented, what a run does with
 * a system that is gone, and when a landscape is written are the importer's business.
 * <p>
 * Nothing here is a conditional request. The upstream computes the entity tag of a model resource over the
 * serialized body, so answering "not modified" costs it a full model load and a full serialization - exactly
 * what answering with the body costs. A conditional request would save this service some bytes and the upstream
 * nothing, and the importer compares what it fetched instead.
 */
@Slf4j
@RequiredArgsConstructor
class ArchRepoModelUpstream implements ArchitectureModelUpstream {

    private final ArchRepoClients clients;

    @Override
    public Set<String> environments() {
        return clients.environments();
    }

    @Override
    public Optional<String> urlOf(String environment) {
        return clients.urlOf(environment);
    }

    /**
     * The systems of an environment.
     * <p>
     * An answer with <b>no list in it</b> is a failure and not a landscape without systems: a run that fetches
     * no system replaces the stored model with an empty one, and Spring hands out a null body for any
     * zero-length {@code 200}. A landscape that really has none answers with an empty list, which is a
     * different thing and passes here.
     */
    @Override
    public List<String> systemNames(String environment) {
        DocsApiClient client = clientFor(environment);
        DocsApiDtos.SystemListDto list = read(environment, () -> clients.retrying(client::systems));
        if (list == null || list.systems() == null) {
            throw new ArchitectureModelUnavailableException((
                    "The architecture repository of the environment %s at %s answered the system list without "
                    + "a list of systems. The stored model is kept: a run that fetches no system would replace "
                    + "the documentation of the whole environment with nothing.")
                    .formatted(environment, clients.urlOf(environment).orElse("")));
        }
        return list.systems().stream().map(DocsApiDtos.SystemSummaryDto::name).toList();
    }

    @Override
    public Optional<SystemTopology> topology(String environment, String system) {
        DocsApiClient client = clientFor(environment);
        return whatIsThere(environment, () -> clients.retrying(() -> client.system(system)))
                .map(ArchRepoModelUpstream::topologyOf);
    }

    @Override
    public Optional<List<DocumentedMessage>> messages(String environment, String system) {
        DocsApiClient client = clientFor(environment);
        return whatIsThere(environment, () -> clients.retrying(() -> client.messages(system)))
                .map(messages -> messagesOf(messages, system));
    }

    private DocsApiClient clientFor(String environment) {
        return clients.of(environment).orElseThrow(() -> new ArchitectureModelUnavailableException(
                "No architecture repository is configured for the environment " + environment + "."));
    }

    /**
     * Reads one resource, turning a failure into the one exception the importer decides on.
     */
    private <T> T read(String environment, Supplier<T> request) {
        try {
            return request.get();
        } catch (ArchRepoException e) {
            throw unavailable(environment, clients.urlOf(environment).orElse(""), e);
        } catch (RuntimeException e) {
            throw new ArchitectureModelUnavailableException(
                    "The architecture repository of the environment %s at %s could not be reached: %s"
                            .formatted(environment, clients.urlOf(environment).orElse(""), e.getMessage()), e);
        }
    }

    /**
     * The same, except that a {@code 404} reads as "not there" rather than as a failure. A system listed a
     * moment ago and gone now is a race between two requests, and the importer has its own answer for it.
     */
    private <T> Optional<T> whatIsThere(String environment, Supplier<T> request) {
        try {
            return Optional.ofNullable(request.get());
        } catch (ArchRepoException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw unavailable(environment, clients.urlOf(environment).orElse(""), e);
        } catch (RuntimeException e) {
            throw new ArchitectureModelUnavailableException(
                    "The architecture repository of the environment %s at %s could not be reached: %s"
                            .formatted(environment, clients.urlOf(environment).orElse(""), e.getMessage()), e);
        }
    }

    static ArchitectureModelUnavailableException unavailable(String environment, String upstream,
                                                             ArchRepoException e) {
        if (e.isUnauthorized()) {
            return new ArchitectureModelUnavailableException(
                    ("The doc service is not allowed to read the architecture model of the environment %s at "
                     + "%s (%d). Check the client registration configured in "
                     + "jeap.doc.archrepo.environments.%s.client-registration and that its client has the role "
                     + "<system-name>_@architecture-model_#read on the architecture repository.")
                            .formatted(environment, upstream, e.getStatus(), environment), e);
        }
        return new ArchitectureModelUnavailableException(
                "The architecture repository of the environment %s at %s answered %d%s."
                        .formatted(environment, upstream, e.getStatus(),
                                e.getProblemType() == null ? "" : " (" + e.getProblemType() + ")"), e);
    }

    private static SystemTopology topologyOf(DocsApiDtos.SystemDetailDto detail) {
        return new SystemTopology(
                detail.name(),
                detail.description(),
                orEmpty(detail.aliases()),
                teamOf(detail.team()),
                orEmpty(detail.components()).stream().map(ArchRepoModelUpstream::componentOf).toList(),
                orEmpty(detail.relations()).stream().map(ArchRepoModelUpstream::relationOf).toList());
    }

    /**
     * The component without its slug, which the importer derives - it is this service's decision how a name
     * becomes a path segment, and not the architecture repository's.
     */
    private static DocumentedComponent componentOf(DocsApiDtos.ComponentDto component) {
        return new DocumentedComponent(
                component.name(),
                null,
                component.description(),
                ComponentType.of(component.type()),
                teamOf(component.team()),
                component.importer(),
                component.lastSeen(),
                orEmpty(component.restApis()).stream()
                        .map(api -> new RestApiOperation(api.method(), api.path())).toList(),
                Optional.ofNullable(component.openApi())
                        .map(api -> new OpenApiReference(api.version(), api.serverUrl(), api.contentUrl(),
                                api.swaggerUrl()))
                        .orElse(null),
                Optional.ofNullable(component.databaseSchema())
                        .map(schema -> new DatabaseSchemaReference(schema.schemaVersion(), schema.contentUrl()))
                        .orElse(null));
    }

    private static SystemRelation relationOf(DocsApiDtos.RelationDto relation) {
        return new SystemRelation(
                RelationKind.of(relation.type()),
                relation.consumerSystem(), relation.consumer(),
                relation.providerSystem(), relation.provider(),
                relation.messageType(), relation.method(), relation.path(), relation.pactUrl());
    }

    private static List<DocumentedMessage> messagesOf(DocsApiDtos.MessageListDto messages, String system) {
        if (messages == null || messages.messages() == null) {
            return List.of();
        }
        List<DocumentedMessage> documented = new ArrayList<>();
        for (DocsApiDtos.MessageDto message : messages.messages()) {
            if (message.name() == null || message.name().isBlank()) {
                log.warn("A message of the system {} has no name and is not documented.", system);
                continue;
            }
            // There are two kinds and a message is filed under one of them, so an unrecognised kind is read as
            // an event. That is a guess, and a guess has to be visible.
            if (!MessageKind.isKnown(message.kind())) {
                log.warn("The message {} of the system {} has the kind '{}', which this service does not know. "
                         + "It is documented as an event.", message.name(), system, message.kind());
            }
            // The slug is the importer's to derive, like a component's - see DocumentedMessage.withSlug.
            documented.add(new DocumentedMessage(
                    message.name(),
                    null,
                    MessageKind.of(message.kind()),
                    message.scope(),
                    message.topic(),
                    message.description(),
                    message.descriptorUrl(),
                    message.documentationUrl(),
                    // The model export carries versions as plain strings; the schemas behind them are
                    // replicated apart and joined in when a page is written.
                    orEmpty(message.versions()).stream().map(DocumentedMessageVersion::of).toList(),
                    orEmpty(message.contracts()).stream()
                            .map(contract -> new MessageContract(ContractRole.of(contract.role()),
                                    contract.component(), contract.system(), contract.topic(),
                                    orEmpty(contract.versions())))
                            .toList()));
        }
        return documented;
    }

    private static Team teamOf(DocsApiDtos.TeamDto team) {
        return team == null ? null
                : new Team(team.name(), team.contactAddress(), team.jiraLink(), team.confluenceLink());
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
