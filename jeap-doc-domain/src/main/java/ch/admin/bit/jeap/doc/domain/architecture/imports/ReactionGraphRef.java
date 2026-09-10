package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;

/**
 * A reaction graph without its content: what the comparison between what is stored and what the observer lists
 * works on.
 * <p>
 * The counterpart of {@link ArchitectureArtifactRef}, and for the same reason - deciding what to fetch must
 * never read a blob out of the database.
 *
 * @param name         the system, component or message type the graph is of, as the <b>model</b> spells it.
 *                     The observer's spelling until {@code ReactionImportStep} has resolved it, which it does
 *                     before anything compares a reference with a stored one
 * @param upstreamName what the observer called the same thing, kept through the resolution because a graph
 *                     that turns up under an unexpected name is otherwise impossible to explain
 * @param variant   the variant of a message type, or {@code ""} for none. Empty for a system and a component,
 *                  which have no variants
 * @param system    the system a component's reactions were published under - the model's spelling once the
 *                  import has resolved it. {@code ""} for the other two kinds, and <b>part of what addresses
 *                  a component's graph</b>: two systems may each call a component {@code gateway}, and one
 *                  row for both would draw one system's reactions on the other's page
 * @param etag      the entity tag of the graph resource, verbatim. For a message type it covers every variant
 *                  the resource answers with, so all the refs of one type carry the same one
 * @param path      where the graph is served, as the index gave it. Null on a reference read from the store,
 *                  which has no reason to keep it
 * @param checkedAt when this service last stored or confirmed the graph. Null on an index entry, which has not
 *                  been
 * @param drawable  whether the stored graph has a node this version draws. False is a graph that is stored and
 *                  gets no page, which is what a link into it must not be written from - see
 *                  {@link ReactionGraph#drawableNodes()}. True on an index entry, which is not stored yet
 */
public record ReactionGraphRef(
        String environment,
        ArchitectureImportKind kind,
        String name,
        String upstreamName,
        String variant,
        String system,
        String etag,
        String path,
        Instant checkedAt,
        boolean drawable) {

    /** The variant of a message type that has none, and of a graph that cannot have one. */
    public static final String NO_VARIANT = "";

    /** The system of a graph that is no component's - a system's own, and a message type's. */
    public static final String NO_SYSTEM = "";

    public ReactionGraphRef {
        // Never null, for the reason the variant is not: a unique index does not constrain nulls, so two runs
        // would insert two rows for one graph.
        variant = variant == null ? NO_VARIANT : variant;
        system = system == null ? NO_SYSTEM : system;
    }

    /**
     * Whether the observer is offering the same graph as the one already stored.
     */
    public boolean hasSameContentAs(ReactionGraphRef stored) {
        return stored != null && etag != null && etag.equals(stored.etag());
    }

    /**
     * The same reference under the name the model uses - which is what a graph is stored under, while
     * {@link #upstreamName()} goes on saying where it came from.
     */
    public ReactionGraphRef withName(String resolved, String resolvedSystem) {
        return new ReactionGraphRef(environment, kind, resolved, upstreamName, variant, resolvedSystem, etag,
                path, checkedAt, drawable);
    }
}
