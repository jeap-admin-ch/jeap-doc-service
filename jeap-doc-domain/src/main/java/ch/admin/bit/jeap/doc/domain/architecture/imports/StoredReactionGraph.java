package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One reaction graph as this service keeps it: the bytes the observer served, under the name the model uses.
 * <p>
 * The counterpart of {@link ArchitectureArtifact}. The two names are the point of it: {@code name} is what a
 * page is generated for, so nothing has to resolve anything while a site is built, and {@code upstreamName} is
 * what the observer called the same thing - which is the only way to explain a graph that turned up under a
 * name nobody expected.
 *
 * @param name         the system, component or message type as the <b>stored model</b> spells it
 * @param variant      the variant of a message type, or {@code ""}. Never null, because a unique index does not
 *                     constrain nulls
 * @param system       the system a component's reactions were published under, as the model spells it, or
 *                     {@code ""} for the other two kinds. <b>Part of what addresses the graph</b>: two systems
 *                     may each call a component {@code gateway}
 * @param upstreamName the observer's own spelling - a system name it lower-cased, a component name verbatim
 * @param etag         the entity tag of the graph resource, verbatim. What the next run sends as
 *                     {@code If-None-Match}
 * @param fingerprint  the fingerprint the payload carried, or null if it carried none
 * @param data         the graph, as it arrived. Nothing in the import looks inside it
 * @param drawableNodes how many nodes of it this version draws, as the adapter counted them while reading the
 *                     envelope - see {@link ReactionGraph#drawableNodes()}
 * @param importedAt   when these bytes were last stored
 */
public record StoredReactionGraph(
        String environment,
        ArchitectureImportKind kind,
        String name,
        String variant,
        String system,
        String upstreamName,
        String etag,
        String fingerprint,
        byte[] data,
        int drawableNodes,
        Instant importedAt) {

    public StoredReactionGraph {
        variant = variant == null ? ReactionGraphRef.NO_VARIANT : variant;
        system = system == null ? ReactionGraphRef.NO_SYSTEM : system;
    }

    /**
     * The graph that was fetched for one reference, under the name it is stored as.
     */
    public static StoredReactionGraph of(ReactionGraphRef ref, ReactionGraph graph, Instant importedAt) {
        return new StoredReactionGraph(ref.environment(), ref.kind(), ref.name(), ref.variant(), ref.system(),
                ref.upstreamName(), graph.etag(), graph.fingerprint(), graph.data(), graph.drawableNodes(),
                importedAt);
    }

    /**
     * Value equality over the bytes too, which the generated one would not give for an array.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof StoredReactionGraph graph
               && Objects.equals(environment, graph.environment)
               && kind == graph.kind
               && Objects.equals(name, graph.name)
               && Objects.equals(variant, graph.variant)
               && Objects.equals(system, graph.system)
               && Objects.equals(upstreamName, graph.upstreamName)
               && Objects.equals(etag, graph.etag)
               && Objects.equals(fingerprint, graph.fingerprint)
               && Arrays.equals(data, graph.data)
               && drawableNodes == graph.drawableNodes
               && Objects.equals(importedAt, graph.importedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(environment, kind, name, variant, system, upstreamName, etag, fingerprint,
                Arrays.hashCode(data), drawableNodes, importedAt);
    }

    /**
     * Without the bytes, so that a log line or a test failure does not print a whole graph.
     */
    @Override
    public String toString() {
        return "StoredReactionGraph[%s %s %s%s etag=%s %d bytes]"
                .formatted(environment, kind, name, variant == null || variant.isEmpty() ? "" : "/" + variant,
                        etag, data == null ? 0 : data.length);
    }
}
