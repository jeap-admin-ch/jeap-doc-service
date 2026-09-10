package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraph;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import ch.admin.bit.jeap.doc.upstream.UpstreamException;
import ch.admin.bit.jeap.doc.upstream.UpstreamHttp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The reaction graphs over the replication API of the reaction observer.
 * <p>
 * Three indexes, asked conditionally, and then one fetch per graph whose entity tag moved. Without the indexes
 * this would be a request per system, per component and per message type of the environment on every run - some
 * 1 565 of them on the larger landscape - for a graph the observer rebuilds once a day.
 * <p>
 * <b>The message graphs are the one place this is not the artifact upstream's twin.</b> One request answers
 * every variant of a message type at once, and the entity tag covers all of them, so an index entry becomes one
 * reference per variant and the fetch picks the variant it was asked for out of the answer.
 */
@Slf4j
@RequiredArgsConstructor
class ReactionObserverUpstream implements ReactionGraphUpstream {

    private static final String SYSTEM_INDEX = "/api/graphs/systems";
    private static final String COMPONENT_INDEX = "/api/graphs/components";
    private static final String MESSAGE_INDEX = "/api/graphs/messages";

    /** How a variant is addressed in the answer of a message type's resource - the observer's own rule. */
    private static final String VARIANT_SEPARATOR = "/";

    private final ReactionObserverClients clients;
    private final ReactionObserverProperties properties;

    @Override
    public boolean isConfiguredFor(String environment) {
        return clients.environments().contains(environment);
    }

    @Override
    public Optional<Fetched<List<ReactionGraphRef>>> index(String environment, ArchitectureImportKind kind,
                                                           String knownIndexEtag) {
        RestClient client = clientFor(environment);
        if (kind == ArchitectureImportKind.MESSAGE_REACTIONS) {
            ResponseEntity<ReactionObserverDtos.MessageGraphIndexDto> answer = call(environment, kind,
                    () -> clients.retrying(() -> conditional(client.get().uri(MESSAGE_INDEX), knownIndexEtag)
                            .retrieve()
                            .toEntity(ReactionObserverDtos.MessageGraphIndexDto.class)));
            if (isNotModified(answer)) {
                return Optional.empty();
            }
            return Optional.of(new Fetched<>(messageRefsOf(environment,
                    requireEntries(environment, kind, entriesOf(answer.getBody()))), entityTagOf(answer)));
        }
        ResponseEntity<ReactionObserverDtos.GraphIndexDto> answer = call(environment, kind,
                () -> clients.retrying(() -> conditional(client.get().uri(indexPathOf(kind)), knownIndexEtag)
                        .retrieve()
                        .toEntity(ReactionObserverDtos.GraphIndexDto.class)));
        if (isNotModified(answer)) {
            return Optional.empty();
        }
        return Optional.of(new Fetched<>(refsOf(environment, kind,
                requireEntries(environment, kind, entriesOf(answer.getBody()))), entityTagOf(answer)));
    }

    @Override
    public GraphFetch content(String environment, ReactionGraphRef ref, String knownEtag) {
        RestClient client = clientFor(environment);
        // Resolved against the origin of the observer, never appended to it: the path already carries the
        // observer's context path, and appending would produce it twice and 404 on everything.
        Optional<URI> graph = clients.resolve(environment, ref.path());
        if (graph.isEmpty()) {
            log.warn("The {} of {} in the environment {} is not replicated: its URL cannot be fetched.",
                    ref.kind(), describe(ref), environment);
            return GraphFetch.skipped("its URL cannot be fetched");
        }
        long cap = properties.getMaxGraphSize().toBytes();
        UpstreamHttp.Answer answer;
        try {
            answer = clients.retrying(() -> clients.getBounded(client, graph.get(), knownEtag, cap));
        } catch (UpstreamException e) {
            if (e.isNotFound()) {
                // A graph the index listed and the resource no longer has: the observer refreshed between the
                // two requests, or its statistics window moved past the last reaction. The index is asked
                // unconditionally on the next run, so a graph that comes back is offered again.
                log.debug("The {} of {} went away between the index and the fetch in the environment {}.",
                        ref.kind(), describe(ref), environment);
                return GraphFetch.skipped("it went away between the index and the fetch");
            }
            throw unavailable(environment, e);
        } catch (RuntimeException e) {
            throw unreachable(environment, e);
        }
        if (answer.isNotModified()) {
            if (knownEtag == null || knownEtag.isBlank()) {
                // Nothing was asked conditionally, so there is nothing stored for this to confirm.
                log.warn("The {} of {} in the environment {} answered \"not modified\" to an unconditional "
                         + "request. It is not replicated.", ref.kind(), describe(ref), environment);
                return GraphFetch.skipped("it answered \"not modified\" to an unconditional request");
            }
            return GraphFetch.unchanged();
        }
        if (answer.isRedirect()) {
            log.warn("The {} of {} in the environment {} answered with a redirect to {}, which is not "
                     + "followed. It is not replicated.", ref.kind(), describe(ref), environment,
                    answer.location());
            return GraphFetch.skipped("it answered with a redirect, which is not followed");
        }
        if (answer.tooLarge()) {
            log.warn("The {} of {} in the environment {} is larger than the {} this service stores. It is not "
                     + "replicated.", ref.kind(), describe(ref), environment, properties.getMaxGraphSize());
            return GraphFetch.skipped("it is larger than the %s this service stores"
                    .formatted(properties.getMaxGraphSize()));
        }
        String etag = answer.etag();
        if (etag == null || etag.isBlank()) {
            // What is stored is addressed by its tag: the next run asks conditionally with it. A graph without
            // one is left where it is rather than stored under nothing.
            log.warn("The {} of {} in the environment {} arrived without an entity tag. It is not replicated.",
                    ref.kind(), describe(ref), environment);
            return GraphFetch.skipped("it arrived without an entity tag");
        }
        return graphOf(environment, ref, etag, answer.body());
    }

    /**
     * The graph of one reference out of the answer that carried it.
     * <p>
     * What is stored is the bytes of the {@code graph} field and the fingerprint beside it - not the envelope,
     * which is the observer's way of answering rather than part of the graph. For a message type the envelope
     * is a map of variant keys, and the one this reference names is picked out of it.
     */
    private GraphFetch graphOf(String environment, ReactionGraphRef ref, String etag, byte[] body) {
        JsonNode answer;
        try {
            answer = clients.readTree(body);
        } catch (JacksonException e) {
            // One unreadable graph costs one graph. It is the observer's payload and not something this
            // service can correct, so it is skipped and offered again on the next run.
            log.warn("The {} of {} in the environment {} is not readable as JSON. It is not replicated.",
                    ref.kind(), describe(ref), environment, e);
            return GraphFetch.skipped("it is not readable as JSON");
        }
        JsonNode envelope = ref.kind() == ArchitectureImportKind.MESSAGE_REACTIONS
                ? answer.path(variantKeyOf(ref))
                : answer;
        JsonNode graph = envelope.path("graph");
        if (graph.isMissingNode() || graph.isNull()) {
            // For a message type this is the variant no longer being among the ones the resource answers,
            // which the next index makes good. For the other kinds it is an answer that is not a graph.
            log.warn("The {} of {} in the environment {} carried no graph. It is not replicated.", ref.kind(),
                    describe(ref), environment);
            return GraphFetch.skipped("it carried no graph");
        }
        JsonNode fingerprint = envelope.path("fingerprint");
        return GraphFetch.stored(new ReactionGraph(etag,
                fingerprint.isString() ? fingerprint.stringValue() : null, clients.writeBytes(graph),
                drawableNodesOf(graph)));
    }

    /**
     * How many nodes of the graph this version would draw.
     * <p>
     * Counted here because this is the one place that has both the payload and its shape in hand - the import
     * stores the bytes and looks inside none of them, and a build that had to answer <i>does this graph draw
     * anything</i> would otherwise read every graph of the environment to find out. Zero is a graph that is
     * stored and gets no page, which is what says a link into that page may not be written.
     */
    private static int drawableNodesOf(JsonNode graph) {
        int drawable = 0;
        for (JsonNode node : graph.path("nodes")) {
            if (ReactionObserverNodes.isDrawable(node)) {
                drawable++;
            }
        }
        return drawable;
    }

    /** The systems or the components of one index, skipping an entry that names nothing. */
    private static List<ReactionGraphRef> refsOf(String environment, ArchitectureImportKind kind,
                                                 List<ReactionObserverDtos.GraphIndexEntryDto> entries) {
        return entries.stream()
                .filter(entry -> entry != null && entry.name() != null && !entry.name().isBlank())
                .map(entry -> new ReactionGraphRef(environment, kind, entry.name(), entry.name(),
                        ReactionGraphRef.NO_VARIANT, entry.system(), entry.etag(), entry.path(), null, true))
                .toList();
    }

    /**
     * One reference per variant of every message type the index lists.
     * <p>
     * The keys the index carries are what the resource answers with - the type alone, or {@code type/variant} -
     * so the variant is what is left of a key once the type in front of it is removed. That is safe here and
     * nowhere else: the type is known, and a message type whose <b>name</b> contains a slash is therefore not
     * mistaken for a variant of a shorter type. A type with no variants at all is listed with one key, which
     * is the type itself.
     */
    private static List<ReactionGraphRef> messageRefsOf(
            String environment, List<ReactionObserverDtos.MessageGraphIndexEntryDto> entries) {
        List<ReactionGraphRef> refs = new ArrayList<>();
        for (ReactionObserverDtos.MessageGraphIndexEntryDto entry : entries) {
            if (entry == null || entry.messageType() == null || entry.messageType().isBlank()) {
                continue;
            }
            List<String> keys = entry.variants() == null || entry.variants().isEmpty()
                    ? List.of(entry.messageType())
                    : entry.variants();
            for (String key : keys) {
                refs.add(new ReactionGraphRef(environment, ArchitectureImportKind.MESSAGE_REACTIONS,
                        entry.messageType(), entry.messageType(), variantOf(entry.messageType(), key), null,
                        entry.etag(), entry.path(), null, true));
            }
        }
        return refs;
    }

    /** The variant a key names, or {@code ""} when the key is the message type itself. */
    private static String variantOf(String messageType, String key) {
        String prefix = messageType + VARIANT_SEPARATOR;
        return key != null && key.startsWith(prefix) ? key.substring(prefix.length())
                : ReactionGraphRef.NO_VARIANT;
    }

    /**
     * How a reference addresses itself in the answer of its message type's resource.
     * <p>
     * <b>The observer's own spelling</b>, not the model's: by the time a graph is fetched the import has
     * resolved the name against the model, and the keys of the answer are the ones the index carried. Keyed by
     * the resolved name, a message type the model spells differently loses every variant it has - and skipping
     * them is also what makes the index be asked unconditionally for ever.
     */
    private static String variantKeyOf(ReactionGraphRef ref) {
        String upstream = ref.upstreamName() == null ? ref.name() : ref.upstreamName();
        return ref.variant() == null || ref.variant().isEmpty() ? upstream
                : upstream + VARIANT_SEPARATOR + ref.variant();
    }

    /**
     * The entries of an index.
     * <p>
     * An answer with <b>no entries at all</b> - no body, or a body without the field - is a failure rather
     * than an empty index, for the same reason a {@code 404} on an index is one: what a run does with an index
     * that lists nothing is decided by the step, and Spring hands out a null body for any zero-length
     * {@code 200}. An index that genuinely lists none arrives as an empty list, which is a landscape nothing
     * has been observed in and is passed through.
     */
    private static <T> List<T> requireEntries(String environment, ArchitectureImportKind kind,
                                              List<T> entries) {
        if (entries == null) {
            throw new ArchitectureModelUnavailableException((
                    "The reaction observer of the environment %s answered the %s index without a list of "
                    + "entries. What is stored is kept: an answer that lists nothing is not an index that is "
                    + "empty.").formatted(environment, kind));
        }
        return entries;
    }

    private static List<ReactionObserverDtos.GraphIndexEntryDto> entriesOf(
            ReactionObserverDtos.GraphIndexDto index) {
        return index == null ? null : index.entries();
    }

    private static List<ReactionObserverDtos.MessageGraphIndexEntryDto> entriesOf(
            ReactionObserverDtos.MessageGraphIndexDto index) {
        return index == null ? null : index.entries();
    }

    private RestClient clientFor(String environment) {
        return clients.of(environment).orElseThrow(() -> new ArchitectureModelUnavailableException(
                "No reaction observer is configured for the environment " + environment + "."));
    }

    /** Which index one kind is listed by. A kind that is not a reaction has none, and says so. */
    private static String indexPathOf(ArchitectureImportKind kind) {
        return switch (kind) {
            case SYSTEM_REACTIONS -> SYSTEM_INDEX;
            case COMPONENT_REACTIONS -> COMPONENT_INDEX;
            case MESSAGE_REACTIONS -> MESSAGE_INDEX;
            case MODEL, OPENAPI_SPEC, DATABASE_SCHEMA, MESSAGE_SCHEMA -> throw new IllegalArgumentException(
                    "The reaction observer serves no index of " + kind + ".");
        };
    }

    /**
     * Adds {@code If-None-Match} with the stored tag <b>verbatim, quotes and all</b>. It is the header's own
     * syntax on both sides, and a mismatch there does not fail - it silently refetches everything on every
     * run, for ever.
     */
    private static RestClient.RequestHeadersSpec<?> conditional(RestClient.RequestHeadersSpec<?> request,
                                                                String knownEtag) {
        return knownEtag == null || knownEtag.isBlank() ? request
                : request.header(HttpHeaders.IF_NONE_MATCH, knownEtag);
    }

    /**
     * By value and not by identity, which is how {@link UpstreamHttp.Answer#isNotModified()} compares it: an
     * {@code HttpStatusCode} need not be the enum constant, and the two sides of this client must not answer
     * the same question two ways.
     */
    private static boolean isNotModified(ResponseEntity<?> answer) {
        return answer.getStatusCode().value() == HttpStatus.NOT_MODIFIED.value();
    }

    private static String entityTagOf(ResponseEntity<?> answer) {
        return answer.getHeaders().getFirst(HttpHeaders.ETAG);
    }

    /**
     * Runs one index request, turning a failure into the one exception the importer decides on.
     * <p>
     * <b>A {@code 404} says the observer is too old</b>, and says so rather than reading as an index that is
     * empty: the indexes and the resource server this client authenticates against arrived in the same release
     * of the observer, so an observer without them is one an environment should not have been configured
     * against. Read as an empty index it would instead delete every graph of the environment.
     */
    private <T> T call(String environment, ArchitectureImportKind kind, Supplier<T> request) {
        try {
            return request.get();
        } catch (UpstreamException e) {
            if (e.isNotFound()) {
                throw new ArchitectureModelUnavailableException((
                        "The reaction observer of the environment %s at %s serves no %s index (404). It is "
                        + "older than the release that serves them, which is also the release that requires a "
                        + "resource server - so either upgrade it or take the environment out of "
                        + "jeap.doc.reactions.environments.")
                        .formatted(environment, urlOf(environment), kind), e);
            }
            throw unavailable(environment, e);
        } catch (RuntimeException e) {
            throw unreachable(environment, e);
        }
    }

    private ArchitectureModelUnavailableException unavailable(String environment, UpstreamException e) {
        if (e.isUnauthorized()) {
            return new ArchitectureModelUnavailableException((
                    "The doc service is not allowed to read the reactions of the environment %s at %s (%d). "
                    + "Check the client registration configured in "
                    + "jeap.doc.reactions.environments.%s.client-registration and that its client has the role "
                    + "<system-name>_@reactions_#read on the reaction observer.")
                    .formatted(environment, urlOf(environment), e.getStatus(), environment), e);
        }
        return new ArchitectureModelUnavailableException("The reaction observer of the environment %s at %s "
                                                         + "answered %d."
                .formatted(environment, urlOf(environment), e.getStatus()), e);
    }

    private ArchitectureModelUnavailableException unreachable(String environment, RuntimeException e) {
        return new ArchitectureModelUnavailableException(
                "The reaction observer of the environment %s at %s could not be reached: %s"
                        .formatted(environment, urlOf(environment), e.getMessage()), e);
    }

    private String urlOf(String environment) {
        return clients.urlOf(environment).orElse("");
    }

    /** What a log line calls one graph: a name, and the variant when there is one. */
    private static String describe(ReactionGraphRef ref) {
        return ref.variant() == null || ref.variant().isEmpty() ? ref.name()
                : ref.name() + " (variant " + ref.variant() + ")";
    }
}
