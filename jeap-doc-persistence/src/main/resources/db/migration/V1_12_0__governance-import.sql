-- What the governance import stores: the state of a run, per kind, and the resources it read from the
-- Governance Service's /docs-api - the two indexes and the systems and rules they list.

-- What the last import of one kind (SYSTEM or RULE) did. Unlike architecture_import there is no environment:
-- the Governance Service is read once per instance.
create table governance_import
(
    kind            varchar not null primary key,
    -- The entity tag of the index, kept even when it is not trusted for a conditional request - see
    -- complete below and GovernanceImportState.conditionalIndexEtag.
    index_etag      varchar,
    -- Whether the last run got through its whole list. An index that answers 304 says the data is unchanged,
    -- not that everything in it was fetched, so a run that could not replicate an entry must not trust that
    -- answer next time.
    complete        boolean                  not null default false,
    item_count      integer                  not null default 0,
    -- What the last run did, as the name of a GovernanceImportOutcome. Null until a kind has run.
    last_outcome    varchar,
    last_attempt_at timestamp with time zone,
    last_success_at timestamp with time zone,
    failure_reason  varchar
);

-- One resource read from the Governance Service's /docs-api: a systems or rules index, or one system or rule
-- resource one of those indexes named. The content is stored verbatim, as JSON - the entity tag names those
-- bytes, and nothing here parses them; the content source reads them back when it writes a page.
create table governance_resource
(
    id            bigint                   not null primary key,
    kind          varchar                  not null,
    -- The system name or rule ID, null for the SYSTEMS_INDEX and RULES_INDEX rows, of which there is at most
    -- one each.
    resource_key  varchar,
    -- The header value verbatim, quotes and all: it is compared against what an index lists and sent back as
    -- If-None-Match, and both are the header's own syntax.
    etag          varchar                  not null,
    content       bytea                    not null,
    -- Written when the content is stored, not derived: Hibernate maps both length() and octet_length() to a
    -- function that rejects byte[].
    size_in_bytes bigint                   not null,
    imported_at   timestamp with time zone not null,
    -- When the entity tag was last confirmed unchanged, without rewriting the content.
    checked_at    timestamp with time zone not null
);
-- One resource per kind and key. A null key is folded to '' so that the index still enforces at most one
-- SYSTEMS_INDEX and one RULES_INDEX row - PostgreSQL does not constrain two nulls to be equal otherwise.
-- Folded on the key for the same reason architecture_artifact folds its names: a key the database enforces
-- and a key the code compares have to be the same key.
create unique index governance_resource_identity
    on governance_resource (kind, lower(coalesce(resource_key, '')));

create sequence governance_resource_id_seq increment by 1;
