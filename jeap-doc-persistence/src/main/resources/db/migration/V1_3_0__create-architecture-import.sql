-- What the architecture import stores: the state of a run, the model it replicates, the artifacts and the
-- message schemas beside it - and, at the end, what a build did to the memory of its container.

-- What the last import of one environment and kind did. It is the evidence an operator reads, the memory that
-- lets a run skip work the one before it already did, and what the staleness gauge is read from - the role
-- documentation_build plays for a build.
create table architecture_import
(
    environment     varchar                  not null,
    kind            varchar                  not null,
    -- The hash of the whole landscape as it was last fetched, for the model only: a run whose fetch hashes to
    -- this writes nothing. Without it every run would rewrite every row to store what was already there.
    content_hash    varchar,
    -- The entity tag of the artifact index, for the artifact kinds only.
    index_etag      varchar,
    -- Whether the last run got through its whole list. An index that answers 304 says the landscape is
    -- unchanged, not that everything in it was fetched, so a run that stopped early must not trust that answer
    -- next time.
    complete        boolean                  not null default false,
    item_count      integer                  not null default 0,
    -- What the last run did, as the name of an ImportOutcome. last_success_at and failure_reason together say
    -- 'succeeded' or 'failed', and a run that stopped at its deadline is neither - it stored what it had
    -- reached. Inferring the outcome would report that PARTIAL run as FAILED, which is the one outcome most
    -- worth telling apart, because it is what a landscape grown past its budget looks like. Null until an
    -- environment and kind has run.
    last_outcome    varchar,
    last_attempt_at timestamp with time zone,
    last_success_at timestamp with time zone,
    failure_reason  varchar,
    primary key (environment, kind)
);

-- A team, shared by the systems and components it owns. One row per team per environment: the architecture
-- repository has no team identity beyond the name, and forty systems of one team must not mean forty copies of
-- its links.
create table architecture_team
(
    id              bigint  not null primary key,
    environment     varchar not null,
    name            varchar not null,
    contact_address varchar,
    jira_link       varchar,
    confluence_link varchar
);
create unique index architecture_team_identity on architecture_team (environment, name);

-- The systems of one environment. Everything below cascades from here, which is what makes replacing a whole
-- landscape two delete statements.
create table architecture_system
(
    id          bigint                   not null primary key,
    environment varchar                  not null,
    name        varchar                  not null,
    slug        varchar                  not null,
    description varchar,
    team_id     bigint references architecture_team (id),
    imported_at timestamp with time zone not null
);
-- The slug is the path segment, so it is the identity. Two systems whose names differ only in case are one
-- tree, and the import refuses the second rather than letting it overwrite the first.
create unique index architecture_system_identity on architecture_system (environment, slug);

-- Ordinal columns everywhere a list is stored: the pages are ordered, and a set-valued join would make a
-- diagram redraw itself between two builds for no reason.
create table architecture_system_alias
(
    system_id bigint  not null references architecture_system (id) on delete cascade,
    ordinal   integer not null,
    alias     varchar not null,
    primary key (system_id, ordinal)
);

create table architecture_component
(
    id                    bigint  not null primary key,
    system_id             bigint  not null references architecture_system (id) on delete cascade,
    ordinal               integer not null,
    name                  varchar not null,
    slug                  varchar not null,
    description           varchar,
    type                  varchar not null,
    team_id               bigint references architecture_team (id),
    importer              varchar,
    last_seen             timestamp with time zone,
    -- The zone the architecture repository meant, kept so that "how old is this" is answered in it. The
    -- timestamp and this together are the ZonedDateTime again.
    last_seen_zone        varchar,
    openapi_version       varchar,
    openapi_server_url    varchar,
    openapi_content_url   varchar,
    openapi_swagger_url   varchar,
    db_schema_version     varchar,
    db_schema_content_url varchar
);
create unique index architecture_component_identity on architecture_component (system_id, slug);

create table architecture_rest_api
(
    component_id bigint  not null references architecture_component (id) on delete cascade,
    ordinal      integer not null,
    method       varchar,
    path         varchar not null,
    primary key (component_id, ordinal)
);

-- A relation belongs to the system that defines it, which is why a context view has to read across every
-- system rather than only the one it is drawing.
create table architecture_relation
(
    id              bigint  not null primary key,
    system_id       bigint  not null references architecture_system (id) on delete cascade,
    ordinal         integer not null,
    kind            varchar not null,
    consumer_system varchar,
    consumer        varchar,
    provider_system varchar,
    provider        varchar,
    message_type    varchar,
    method          varchar,
    path            varchar,
    pact_url        varchar
);

create table architecture_message
(
    id                bigint  not null primary key,
    system_id         bigint  not null references architecture_system (id) on delete cascade,
    ordinal           integer not null,
    name              varchar not null,
    -- The path segment a message is documented under, derived from its name and handed out by the importer
    -- like a system's or a component's. Nullable, and the reader derives it from the name where it is absent.
    slug              varchar,
    kind              varchar not null,
    scope             varchar,
    topic             varchar,
    description       varchar,
    descriptor_url    varchar,
    documentation_url varchar
);
create unique index architecture_message_identity on architecture_message (system_id, name);
create unique index architecture_message_slug on architecture_message (system_id, slug);

create table architecture_message_version
(
    message_id bigint  not null references architecture_message (id) on delete cascade,
    ordinal    integer not null,
    version    varchar not null,
    primary key (message_id, ordinal)
);

create table architecture_message_contract
(
    id             bigint  not null primary key,
    message_id     bigint  not null references architecture_message (id) on delete cascade,
    ordinal        integer not null,
    role           varchar not null,
    component_name varchar,
    system_name    varchar,
    topic          varchar
);

create table architecture_message_contract_version
(
    contract_id bigint  not null references architecture_message_contract (id) on delete cascade,
    ordinal     integer not null,
    version     varchar not null,
    primary key (contract_id, ordinal)
);

-- The indexes the hourly import needs. Every other child of a system gets a usable index on its foreign key
-- for free, from its composite primary key or its identity index. These two do not, and both are read on every
-- build (`where system_id = any(:ids)`) and cascade-deleted on every import - so without them each replace
-- scans them whole, once per parent row.
create index architecture_relation_system_id on architecture_relation (system_id);
create index architecture_message_contract_message_id on architecture_message_contract (message_id);

-- A team is deleted and re-inserted with the landscape, and the reference to it is NO ACTION rather than a
-- cascade - so PostgreSQL has to prove, per deleted team, that no system and no component still points at it.
-- Without an index that proof is a sequential scan of both tables, once per team.
create index architecture_system_team_id on architecture_system (team_id);
create index architecture_component_team_id on architecture_component (team_id);

-- One replicated artifact: the OpenAPI specification or the database schema of a component, as the
-- architecture repository of one environment publishes it. The content is stored verbatim - the entity tag
-- names those bytes, and a round trip through a decoder would name different ones.
--
-- system_name and component_name are plain strings and there is deliberately no foreign key into the model.
-- The model is replaced wholesale on every import; a reference into it would be cascade-deleted with it and
-- the blob refetched on the next run, which is the exact cost these entity tags exist to avoid. The two halves
-- are joined by name at read time, and orphans are swept at the end of a model import.
create table architecture_artifact
(
    id             bigint                   not null primary key,
    environment    varchar                  not null,
    kind           varchar                  not null,
    system_name    varchar                  not null,
    component_name varchar                  not null,
    version        varchar,
    -- The header value verbatim, quotes and all: it is compared against what the index lists and sent back as
    -- If-None-Match, and both are the header's own syntax.
    etag           varchar                  not null,
    content        bytea                    not null,
    -- Written when the content is stored, not derived: Hibernate maps both length() and octet_length() to a
    -- function that rejects byte[].
    size_in_bytes  bigint                   not null,
    -- When the architecture repository last saw it change, not when this service fetched it.
    modified_at    timestamp with time zone,
    -- When the content was last stored, and when the entity tag was last confirmed unchanged. The second says
    -- the copy is current, the first says how old the bytes are.
    replicated_at  timestamp with time zone not null,
    checked_at     timestamp with time zone not null
);
-- One artifact per component, kind and environment: the architecture repository updates its specification and
-- its schema in place and keeps no history, so there is nothing to choose between.
--
-- **Folded on the two names**, because everything that reads it folds them: the orphan sweep joins the model
-- with lower(...) on both sides, and a model that re-spells a system between two runs would otherwise produce
-- a second row that the unique index does not refuse and the sweep never removes. AGENTS.md states the rule: a
-- key the database enforces and a key the code compares have to be the same key.
create unique index architecture_artifact_identity
    on architecture_artifact (environment, kind, lower(system_name), lower(component_name));

-- The Avro schemas of one message type version, as the architecture repository of one environment renders them.
--
-- system_name and message_name are plain strings and there is deliberately no foreign key into the model, for
-- the same reason architecture_artifact has none: the model is replaced wholesale on every import, and a
-- reference into it would be cascade-deleted and every schema of the landscape refetched. The two halves are
-- joined by name when a page is written.
--
-- A row is replaced in place when the upstream serves the version again with different content. A message type
-- version rarely moves - a changed schema is normally published as a new version - but compatible_version is
-- derived upstream from the version list, so publishing an intermediate version changes what an already
-- published version answers, and an import re-renders the schemas. That is why a run revalidates what it holds
-- with etag rather than trusting it, and why checked_at records when it last did.
create table architecture_message_schema
(
    id                 bigint                   not null primary key,
    environment        varchar                  not null,
    system_name        varchar                  not null,
    message_name       varchar                  not null,
    version            varchar                  not null,
    -- What this version declares against compatible_version, e.g. BACKWARD. Both are null for a version whose
    -- descriptor declares none, which is typically the first version of a message type.
    compatibility_mode varchar,
    compatible_version varchar,
    -- Null together where the message type has no key schema; a version without a value schema cannot be
    -- imported upstream, so the value columns are there in practice.
    key_schema_name    varchar,
    key_schema_url     varchar,
    key_schema         text,
    value_schema_name  varchar,
    value_schema_url   varchar,
    value_schema       text,
    -- The tag the upstream served these bytes under, sent back as If-None-Match by the next run that reaches
    -- this version. Null where the upstream served none, which makes that run ask unconditionally.
    etag               varchar,
    replicated_at      timestamp with time zone not null,
    -- When the version was last stored or confirmed - what a run orders its revalidations by, oldest first.
    checked_at         timestamp with time zone not null
);

-- What makes two rows the same version: the lookup the import does before it stores one, and the index that
-- refuses a second row for a version the upstream lists twice.
--
-- Folded on the two names, like the artifact identity above: the architecture model and these rows carry the
-- spellings of two different exports of the same upstream - an alias or a differently-cased path resolves to
-- the stored spelling - so a case-sensitive identity would let one version become two rows: both shown on the
-- page, neither pruned, because the import compares identities the same way. The folded columns lead the
-- index, so the read a generation run makes - every schema of one system of one environment - is served by it
-- and needs no index of its own.
create unique index architecture_message_schema_identity
    on architecture_message_schema (environment, lower(system_name), lower(message_name), version);

create sequence architecture_team_id_seq increment by 1;
create sequence architecture_system_id_seq increment by 1;
create sequence architecture_component_id_seq increment by 1;
create sequence architecture_relation_id_seq increment by 1;
create sequence architecture_message_id_seq increment by 1;
create sequence architecture_message_contract_id_seq increment by 1;
create sequence architecture_artifact_id_seq increment by 1;
create sequence architecture_message_schema_id_seq increment by 1;

-- What a build did to the memory of its container, which otherwise exists only in a log line and in the prose
-- of a failure reason. It is the number a container is sized from: a build is a child process whose bundler
-- allocates outside any heap this service can see, so the JVM's own meters say nothing about it.
--
-- Nullable, and the three are written together. A container whose memory cannot be read - off Linux, or
-- wherever the cgroup files are not there - gives no peak, and a row without one must not read as a build that
-- used no memory.
alter table documentation_build
    add column memory_peak_bytes  bigint,
    add column memory_limit_bytes bigint,
    -- Whether the usage is this build's own peak, or only an upper bound on it: where the kernel does not let
    -- the high-water mark be reset and the build stayed below an earlier one, all that is known is 'at most'.
    add column memory_peak_exact  boolean;
