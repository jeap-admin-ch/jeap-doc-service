package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream;
import ch.admin.bit.jeap.doc.domain.port.SchemaFetch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Avro schemas of the message type versions, over the {@code /docs-api} of the architecture repository.
 * <p>
 * <b>A version is asked about conditionally</b>, exactly like an artifact. It rarely moves - a changed schema is
 * normally published as a new version - but it does: {@code compatibleVersion} is derived upstream from the
 * version list, so publishing an intermediate version changes what an already published version answers, and an
 * import re-renders the schemas. The upstream tags a version over the <b>serialized body</b> - unlike an artifact,
 * whose tag is the hash of its stored bytes - so revalidating costs it a read and a hash, and saves the payload
 * rather than the work. It is done because its docs API says a consumer must not store a version once and never
 * ask again, not because it is free.
 * <p>
 * The <b>index</b> is fetched plainly. Its tag covers the list of versions, which is not the question this step
 * asks - a version can move without the list changing - so a {@code 304} on it would say nothing useful.
 */
@Slf4j
@RequiredArgsConstructor
class ArchRepoMessageSchemaUpstream implements MessageSchemaUpstream {

    private static final String MESSAGE_TYPE_INDEX = "/docs-api/message-types";

    private final ArchRepoClients clients;
    private final ArchitectureImportProperties properties;

    @Override
    public List<MessageVersionRef> index(String environment) {
        RestClient client = clientFor(environment);
        DocsApiDtos.MessageTypeIndexDto body = call(environment, () -> clients.retrying(() ->
                client.get().uri(MESSAGE_TYPE_INDEX)
                        .retrieve()
                        .body(DocsApiDtos.MessageTypeIndexDto.class)));
        return entriesOf(environment, body);
    }

    @Override
    public SchemaFetch version(String environment, MessageVersionRef ref, String knownEtag) {
        // Resolved before the try, not inside it: an environment with no architecture repository configured
        // is a configuration error, and wrapping it in "could not be reached at <nothing>" would name the
        // wrong cause. The index does the same.
        RestClient client = clientFor(environment);
        // Resolved against the origin of the upstream, never appended to it: the path already carries the
        // architecture repository's context path, and appending would produce it twice and 404 on everything.
        Optional<URI> resource = clients.resolve(environment, ref.contentUrl());
        if (resource.isEmpty()) {
            // One entry of an index whose rest is still worth replicating, so this one is skipped rather than
            // the run abandoned. The reason is logged where the URL was rejected.
            log.warn("The schemas of {} {} of the system {} in the environment {} are not replicated: their "
                     + "content URL cannot be fetched.", ref.message(), ref.version(), ref.system(),
                    environment);
            return SchemaFetch.skipped("its content URL cannot be fetched");
        }
        long cap = properties.getMaxArtifactSize().toBytes();
        ArchRepoClients.Answer answer;
        try {
            answer = clients.retrying(() -> clients.getBounded(client, resource.get(), knownEtag, cap));
        } catch (ArchRepoException e) {
            if (e.isNotFound()) {
                // It went away between the index and the fetch - a message type withdrawn while the run was
                // reading. The next run asks the index again and simply does not list it.
                log.debug("The version {} of {} of the system {} went away between the index and the fetch in "
                          + "the environment {}.", ref.version(), ref.message(), ref.system(), environment);
                return SchemaFetch.skipped("it went away between the index and the fetch");
            }
            throw ArchRepoModelUpstream.unavailable(environment, clients.urlOf(environment).orElse(""), e);
        } catch (RuntimeException e) {
            throw new ArchitectureModelUnavailableException(
                    "The architecture repository of the environment %s at %s could not be reached: %s"
                            .formatted(environment, clients.urlOf(environment).orElse(""), e.getMessage()), e);
        }
        if (answer.isNotModified()) {
            if (knownEtag == null || knownEtag.isBlank()) {
                // Nothing was asked conditionally, so there is nothing stored for this to confirm.
                log.warn("The version {} of {} of the system {} in the environment {} answered \"not "
                         + "modified\" to an unconditional request. It is not replicated.", ref.version(),
                        ref.message(), ref.system(), environment);
                return SchemaFetch.skipped("it answered \"not modified\" to an unconditional request");
            }
            return SchemaFetch.unchanged();
        }
        if (answer.isRedirect()) {
            // Redirects are not followed: the origin was checked on the URL the index gave, and a hop off it
            // would store whatever the Location named as this version's schemas.
            log.warn("The version {} of {} of the system {} in the environment {} answered with a redirect to "
                     + "{}, which is not followed. It is not replicated.", ref.version(), ref.message(),
                    ref.system(), environment, answer.location());
            return SchemaFetch.skipped("it answered with a redirect, which is not followed");
        }
        if (answer.tooLarge()) {
            // The answer was never read past the cap, so its size is known only to be over it. What is behind
            // the cap is the same as for an artifact: the renderings go into an unbounded column that a build
            // reads whole, per system, and one this size is a defect upstream.
            log.warn("The answer for the version {} of {} of the system {} in the environment {} is larger "
                     + "than the {} this service stores. It is not replicated.", ref.version(), ref.message(),
                    ref.system(), environment, properties.getMaxArtifactSize());
            return SchemaFetch.skipped("it is larger than the %s this service stores"
                    .formatted(properties.getMaxArtifactSize()));
        }
        DocsApiDtos.MessageTypeVersionDto body = read(environment, ref, answer.body());
        if (body == null) {
            return SchemaFetch.skipped("it arrived without a readable body");
        }
        // A version without a tag is stored all the same, unlike an artifact: an artifact is addressed by its
        // tag, a version is addressed by its three names. The next run simply asks for it unconditionally.
        return SchemaFetch.stored(schemasOf(environment, ref, body, answer.etag()));
    }

    /**
     * The payload of one version, or null when there is none to read.
     * <p>
     * The bytes were read rather than converted, because that is what bounding them at the cap needs - so the
     * payload is parsed here instead of by the client's message converters. A body that is not the JSON this
     * expects is one version's problem and not the run's: the rest of the index is still worth replicating.
     */
    private DocsApiDtos.MessageTypeVersionDto read(String environment, MessageVersionRef ref, byte[] body) {
        if (body == null || body.length == 0) {
            log.warn("The version {} of {} of the system {} in the environment {} arrived without a body. It "
                     + "is not replicated.", ref.version(), ref.message(), ref.system(), environment);
            return null;
        }
        try {
            return clients.readJson(body, DocsApiDtos.MessageTypeVersionDto.class);
        } catch (JacksonException e) {
            log.warn("The version {} of {} of the system {} in the environment {} did not arrive as readable "
                     + "JSON. It is not replicated.", ref.version(), ref.message(), ref.system(), environment,
                    e);
            return null;
        }
    }

    /**
     * The stored row of one fetched version.
     * <p>
     * The names come from the <b>payload</b> rather than from the index entry, because the upstream answers
     * with the system and the message type as it stores them: an alias or a differently-cased path resolves to
     * the stored spelling, and the model this is joined to by name carries that spelling.
     */
    private static MessageVersionSchemas schemasOf(String environment, MessageVersionRef ref,
                                                   DocsApiDtos.MessageTypeVersionDto body, String etag) {
        return new MessageVersionSchemas(environment,
                body.system() == null ? ref.system() : body.system(),
                body.message() == null ? ref.message() : body.message(),
                body.version() == null ? ref.version() : body.version(),
                body.compatibilityMode(), body.compatibleVersion(),
                schemaOf(body.key()), schemaOf(body.value()), etag, Instant.now());
    }

    private static MessageSchema schemaOf(DocsApiDtos.MessageSchemaDto schema) {
        if (schema == null) {
            return null;
        }
        MessageSchema read = new MessageSchema(schema.schemaName(), schema.schemaUrl(),
                schema.resolvedSchema());
        return read.isEmpty() ? null : read;
    }

    /**
     * The index, flattened to one entry per version - <b>at most one</b>.
     * <p>
     * An entry naming no system, no message type or no version is left out rather than stored under a null:
     * what the store is keyed by is exactly those three, and the model is joined to it by the first two.
     * <p>
     * <b>The kind is dropped, so the flattening has to deduplicate.</b> The upstream groups its index by
     * system, kind and message type, while the resource of one version is addressed by system, message type
     * and version alone - so a system that defines an event and a command of the same name lists the same
     * version twice, under the same content URL. This service keys a version by the three the resource is
     * addressed by, so the two are one row; emitting both would have the run store it twice and violate the
     * unique index on them.
     * <p>
     * An answer with <b>no list in it</b> is a failure rather than an index of nothing, exactly as it is for
     * the artifact indexes: Spring hands out a null body for any zero-length {@code 200}, and a run that lists
     * nothing would report a success that stored nothing at all.
     */
    private static List<MessageVersionRef> entriesOf(String environment,
                                                     DocsApiDtos.MessageTypeIndexDto body) {
        if (body == null || body.messageTypes() == null) {
            throw new ArchitectureModelUnavailableException((
                    "The architecture repository of the environment %s answered the message type index without "
                    + "a list of message types. An answer that lists nothing is not an index that is empty.")
                    .formatted(environment));
        }
        Map<String, MessageVersionRef> refs = new LinkedHashMap<>();
        for (DocsApiDtos.MessageTypeIndexEntryDto messageType : body.messageTypes()) {
            if (messageType == null || messageType.system() == null || messageType.message() == null
                || messageType.versions() == null) {
                continue;
            }
            for (DocsApiDtos.MessageTypeVersionRefDto version : messageType.versions()) {
                if (version == null || version.version() == null) {
                    continue;
                }
                MessageVersionRef ref = MessageVersionRef.listed(environment, messageType.system(),
                        messageType.message(), version.version(), version.contentUrl());
                refs.putIfAbsent(ref.identity(), ref);
            }
        }
        return List.copyOf(refs.values());
    }

    private RestClient clientFor(String environment) {
        return clients.restClientOf(environment).orElseThrow(() ->
                new ArchitectureModelUnavailableException(
                        "No architecture repository is configured for the environment "
                        + environment + "."));
    }

    private <T> T call(String environment, java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (ArchRepoException e) {
            throw ArchRepoModelUpstream.unavailable(environment, clients.urlOf(environment).orElse(""), e);
        } catch (RuntimeException e) {
            throw new ArchitectureModelUnavailableException(
                    "The architecture repository of the environment %s at %s could not be reached: %s"
                            .formatted(environment, clients.urlOf(environment).orElse(""), e.getMessage()), e);
        }
    }
}
