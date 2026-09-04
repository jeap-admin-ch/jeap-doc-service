package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArtifactFetch;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The OpenAPI specifications and database schemas over the {@code /docs-api} of the architecture repository.
 * <p>
 * Every request here is conditional, and that is the whole point: the upstream tags these from a stored hash,
 * so answering "not modified" costs it nothing and saves a blob on the wire. Almost none of them change between
 * two imports.
 */
@Slf4j
@RequiredArgsConstructor
class ArchRepoArtifactUpstream implements ArchitectureArtifactUpstream {

    private static final String OPENAPI_INDEX = "/docs-api/openapi-specs";
    private static final String DATABASE_SCHEMA_INDEX = "/docs-api/database-schemas";

    private final ArchRepoClients clients;
    private final ArchitectureImportProperties properties;

    @Override
    public Optional<Fetched<List<ArchitectureArtifactRef>>> index(String environment,
                                                                  ArchitectureImportKind kind,
                                                                  String knownIndexEtag) {
        RestClient client = clientFor(environment);
        ResponseEntity<DocsApiDtos.ArtifactIndexDto> answer = call(environment, () -> clients.retrying(() ->
                conditional(client.get().uri(indexPathOf(kind)), knownIndexEtag)
                        .retrieve()
                        .toEntity(DocsApiDtos.ArtifactIndexDto.class)));
        if (answer.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            return Optional.empty();
        }
        return Optional.of(new Fetched<>(entriesOf(environment, kind, answer.getBody()), entityTagOf(answer)));
    }

    @Override
    public ArtifactFetch content(String environment, ArchitectureArtifactRef entry, String knownEtag) {
        RestClient client = clientFor(environment);
        // Resolved against the origin of the upstream, never appended to it: the path already carries the
        // architecture repository's context path, and appending would produce it twice and 404 on everything.
        Optional<URI> content = clients.resolve(environment, entry.contentUrl());
        if (content.isEmpty()) {
            // Not a URI, or not on this upstream. It is one entry of an index, and the rest of the index is
            // still worth replicating - so this one is skipped rather than the run abandoned. The reason is
            // logged where the URL was rejected.
            log.warn("The {} of {}/{} in the environment {} is not replicated: its content URL cannot be "
                     + "fetched.", entry.kind(), entry.system(), entry.component(), environment);
            return ArtifactFetch.skipped("its content URL cannot be fetched");
        }
        long cap = properties.getMaxArtifactSize().toBytes();
        ArchRepoClients.Answer answer;
        try {
            answer = clients.retrying(() -> clients.getBounded(client, content.get(), knownEtag, cap));
        } catch (ArchRepoException e) {
            if (e.isNotFound()) {
                // It went away between the index and the fetch, which is a race and not an error. The index is
                // asked unconditionally on the next run, so an artifact that comes back is offered again.
                log.debug("The {} of {}/{} went away between the index and the fetch in the environment {}.",
                        entry.kind(), entry.system(), entry.component(), environment);
                return ArtifactFetch.skipped("it went away between the index and the fetch");
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
                log.warn("The {} of {}/{} in the environment {} answered \"not modified\" to an unconditional "
                         + "request. It is not replicated.", entry.kind(), entry.system(), entry.component(),
                        environment);
                return ArtifactFetch.skipped("it answered \"not modified\" to an unconditional request");
            }
            return ArtifactFetch.unchanged();
        }
        if (answer.isRedirect()) {
            // Redirects are not followed, because the origin was checked on the URL the index gave and a hop
            // off it would fetch something else entirely. Where the content really is, is the upstream's to
            // say in its index.
            log.warn("The {} of {}/{} in the environment {} answered with a redirect to {}, which is not "
                     + "followed. It is not replicated.", entry.kind(), entry.system(), entry.component(),
                    environment, answer.location());
            return ArtifactFetch.skipped("it answered with a redirect, which is not followed");
        }
        if (answer.tooLarge()) {
            // Left where it is rather than stored, and never read whole. Because it was skipped, the next run
            // asks the index unconditionally and is offered it again - and skips it again until it shrinks,
            // which is the right outcome: a specification this size is a defect upstream, not something to
            // render.
            log.warn("The {} of {}/{} in the environment {} is larger than the {} this service stores. It is "
                     + "not replicated.", entry.kind(), entry.system(), entry.component(), environment,
                    properties.getMaxArtifactSize());
            return ArtifactFetch.skipped("it is larger than the %s this service stores"
                    .formatted(properties.getMaxArtifactSize()));
        }
        String etag = answer.etag();
        if (etag == null || etag.isBlank()) {
            // What is stored is addressed by its tag: the next run asks conditionally with it, and the column
            // that holds it refuses null. An artifact without one is left where it is rather than taking the
            // whole replication down on a constraint the upstream caused.
            log.warn("The {} of {}/{} in the environment {} arrived without an entity tag. It is not "
                     + "replicated.", entry.kind(), entry.system(), entry.component(), environment);
            return ArtifactFetch.skipped("it arrived without an entity tag");
        }
        // An empty body is an empty artifact and is stored as one. It is decided from the status - every
        // error threw, the 304 and the redirect are answered above - and not from whether bytes arrived:
        // skipping a component that publishes an empty specification would skip it on every run for ever, and
        // one skipped entry keeps the whole kind from ever trusting the index tag again.
        byte[] bytes = answer.body();
        return ArtifactFetch.stored(new ArchitectureArtifact(environment, entry.kind(), entry.system(),
                entry.component(), entry.version(), etag, bytes, bytes.length, entry.modifiedAt(),
                Instant.now()));
    }

    /**
     * The entries of an index, skipping any that names no system or no component.
     * <p>
     * An answer with <b>no body at all</b> is a failure rather than an empty index, for the reason a
     * {@code 404} on an index is one: what the run does with an index that lists nothing is delete every
     * artifact it has stored for the environment, and Spring hands out a null body for any zero-length
     * {@code 200} - a proxy, a truncated answer, an upstream that says nothing.
     */
    private static List<ArchitectureArtifactRef> entriesOf(String environment, ArchitectureImportKind kind,
                                                           DocsApiDtos.ArtifactIndexDto body) {
        if (body == null || body.artifacts() == null) {
            throw new ArchitectureModelUnavailableException((
                    "The architecture repository of the environment %s answered the %s index without a list of "
                    + "artifacts. The stored artifacts are kept: an empty index deletes every one of them, and "
                    + "an answer that lists nothing is not an index that is empty.")
                    .formatted(environment, kind));
        }
        return body.artifacts().stream()
                .filter(entry -> entry.system() != null && entry.component() != null)
                .map(entry -> new ArchitectureArtifactRef(environment, kind, entry.system(), entry.component(),
                        entry.version(), entry.etag(),
                        entry.lastModifiedAt() == null ? null : entry.lastModifiedAt().toInstant(),
                        entry.contentUrl(), null))
                .toList();
    }

    private RestClient clientFor(String environment) {
        return clients.restClientOf(environment).orElseThrow(() -> new ArchitectureModelUnavailableException(
                "No architecture repository is configured for the environment " + environment + "."));
    }

    private static String indexPathOf(ArchitectureImportKind kind) {
        return kind == ArchitectureImportKind.DATABASE_SCHEMA ? DATABASE_SCHEMA_INDEX : OPENAPI_INDEX;
    }

    /**
     * Adds {@code If-None-Match} with the stored tag <b>verbatim, quotes and all</b>. It is the header's own
     * syntax on both sides: unquoting it on the way in would mean re-quoting it on the way out, and a mismatch
     * there does not fail - it silently refetches every artifact on every run, for ever.
     */
    private static RestClient.RequestHeadersSpec<?> conditional(RestClient.RequestHeadersSpec<?> request,
                                                                String knownEtag) {
        return knownEtag == null || knownEtag.isBlank() ? request
                : request.header(HttpHeaders.IF_NONE_MATCH, knownEtag);
    }

    /** The entity tag verbatim, as it arrived. */
    private static String entityTagOf(ResponseEntity<?> answer) {
        return answer.getHeaders().getFirst(HttpHeaders.ETAG);
    }

    /**
     * Runs one request, turning a failure into the one exception the importer decides on.
     * <p>
     * A {@code 404} on an <b>index</b> is a failure like any other and not an empty index: an architecture
     * repository too old to serve it would otherwise look like one that publishes nothing, and the run would
     * delete every stored artifact.
     */
    private <T> T call(String environment, Supplier<T> request) {
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
