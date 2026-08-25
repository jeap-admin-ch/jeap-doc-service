-- The lock of the scheduled jobs: of several instances of the doc service, one runs a job and the others skip it.
create table shedlock
(
    name       varchar(64)  not null primary key,
    lock_until timestamp    not null,
    locked_at  timestamp    not null,
    locked_by  varchar(255) not null
);

-- Incremented by one, which is what the allocation size of the entity expects.
create sequence documentation_subject_id_seq increment by 1;

create table documentation_subject
(
    id          bigint                   not null primary key,
    site        varchar                  not null,
    kind        varchar                  not null,
    system_name varchar                  not null,
    name        varchar,
    created_at  timestamp with time zone not null
);

-- What identifies a subject: its system, and - for a component or a library - the name of that component or
-- library, which a system does not have next to its system_name. In PostgreSQL two null names would not
-- conflict, so a missing name is compared as an empty one.
create unique index documentation_subject_identity
    on documentation_subject (site, kind, system_name, coalesce(name, ''));

-- Incremented by one, which is what the allocation size of the entity expects.
create sequence documentation_upload_id_seq increment by 1;

create table documentation_upload
(
    id                bigint                   not null primary key,
    upload_id         uuid                     not null,
    subject_id        bigint                   not null references documentation_subject (id),
    template          varchar                  not null,
    source_format     varchar                  not null,
    location          varchar,
    topic             varchar,
    label             varchar,
    version           varchar,
    source_repository varchar                  not null,
    source_revision   varchar                  not null,
    source_ref        varchar                  not null,
    source_timestamp  timestamp with time zone not null,
    build_url         varchar,
    generated_at      timestamp with time zone,
    state             varchar                  not null,
    object_key        varchar,
    -- SHA-256 of the stored bundle, so it can be said later whether what lies in the storage is what a pipeline
    -- sent, and whether two attempts of one upload sent the same.
    bundle_sha256     varchar(64),
    size_in_bytes     bigint                   not null,
    attempt           integer                  not null,
    received_at       timestamp with time zone not null,
    completed_at      timestamp with time zone,
    failure_reason    varchar
);

-- Every column a query looks an upload up by has an index. Not received_at: the only query on it is the nightly
-- clean-up, which is a bulk delete over everything old and scans the table anyway.
-- The upload id is chosen by the client and is what makes a retry a retry: it may exist once.
create unique index documentation_upload_upload_id on documentation_upload (upload_id);
-- The generator asks for the uploads waiting for it.
create index documentation_upload_state on documentation_upload (state);
-- The documentation of one system, component or library.
create index documentation_upload_subject_id on documentation_upload (subject_id);
