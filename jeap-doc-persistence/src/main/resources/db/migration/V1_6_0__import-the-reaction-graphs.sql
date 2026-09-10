-- The reaction graphs replicated from the reaction observer of an environment: which message makes a
-- component react, and what it does in answer.
--
-- One row per graph, and a graph is stored as it arrived. Nothing in the import looks inside it - how a
-- reaction graph is drawn is decided when a page is generated, so a change to the drawing must not need a
-- re-import.
--
-- name is a plain string and there is deliberately no foreign key into the model, for the reason
-- architecture_artifact has none: the model is replaced wholesale on every import, and a reference into it
-- would be cascade-deleted with it and every graph refetched. The two halves are joined by name when a page is
-- written, and that is what name holds - the model's spelling, resolved while importing, aliases included.
-- upstream_name is what the reaction observer called the same thing: it lower-cases a system name and keeps a
-- component name as the publisher sent it, so a graph that turns up under an unexpected name is otherwise
-- impossible to explain.
create table reaction_graph
(
    id            bigint                   not null primary key,
    environment   varchar                  not null,
    -- SYSTEM_REACTIONS, COMPONENT_REACTIONS or MESSAGE_REACTIONS, as the ArchitectureImportKind is spelled.
    kind          varchar                  not null,
    name          varchar                  not null,
    -- The variant of a message type, and '' rather than null for a graph that has none: a unique index does
    -- not constrain nulls, so two runs would insert two rows for the same graph.
    variant       varchar                  not null,
    -- The system a component's reactions were published under, as the model spells it once the import has
    -- resolved it, and '' for the other two kinds - never null, for the reason variant is not. It is the
    -- observer's own answer to a question the architecture repository has to guess by intersecting component
    -- names, and it is part of the identity below: two systems may each call a component 'gateway'.
    system_name   varchar                  not null,
    upstream_name varchar                  not null,
    -- The header value verbatim, quotes and all: it is compared against what the index lists and sent back as
    -- If-None-Match, and both are the header's own syntax. For a message type it covers every variant the
    -- resource answers with, so the rows of one type carry the same one.
    etag          varchar                  not null,
    -- The fingerprint the payload carried, which is the observer's own name for the graph. Null if it carried
    -- none.
    fingerprint   varchar,
    graph_data    bytea                    not null,
    -- How many nodes of the payload the version that stored it would draw, counted by the adapter that read
    -- the envelope. Zero is a graph that is stored and gets no page - which is what says a link into that
    -- page may not be written, without reading every graph of the environment to find out.
    drawable_nodes integer                 not null,
    -- Written when the graph is stored, not derived: Hibernate maps both length() and octet_length() to a
    -- function that rejects byte[]. It is also what answers 'how large are these graphs' without reading one.
    size_in_bytes bigint                   not null,
    -- When the bytes were last stored, and when the entity tag was last confirmed unchanged. The second says
    -- the graph is current, the first says how old it is.
    imported_at   timestamp with time zone not null,
    checked_at    timestamp with time zone not null
);

-- One graph per environment, kind, name, system and variant. The observer keeps no history of a graph - it
-- answers the one it holds - so there is nothing to choose between.
--
-- **The system is part of it, and only a component's graph has one.** Two systems may each call a component
-- 'gateway' - ArchitectureModel.systemsOf says so - and one row for both would store whichever the index
-- listed last and draw it on both systems' pages.
--
-- **Folded on the name and the system, not on the variant.** They are folded because everything that reads
-- them folds them: a model that re-spells a system between two runs would otherwise produce a second row that
-- the unique index does not refuse, and AGENTS.md states the rule - a key the database enforces and a key the
-- code compares have to be the same key. The variant is not folded, because it is the observer's string
-- verbatim and two variants that differ in case are two graphs there.
--
-- **Separate columns, never one joined string.** A message type and a variant joined by a separator that can
-- occur in either makes two different graphs one row, and a prune then takes the neighbour with it.
create unique index reaction_graph_identity
    on reaction_graph (environment, kind, lower(name), lower(system_name), variant);

create sequence reaction_graph_id_seq increment by 1;
