-- The documentation a team uploaded, as the doc service publishes it: a set per subject and format, and its
-- files. Separate from documentation_upload, which is the record of a request, and from the architecture
-- tables, which are a replica of an upstream. Nothing joins the three in the database.

-- Incremented by one, which is what the allocation size of the entity expects.
create sequence custom_set_id_seq increment by 1;

create table custom_set
(
    id                bigint                   not null primary key,
    site              varchar                  not null,
    kind              varchar                  not null,
    system_name       varchar                  not null,
    -- The component or library the set documents, null for a system.
    name              varchar,
    source_format     varchar                  not null,
    template          varchar                  not null,
    -- The section and slug an HTML microsite is embedded under; null for markdown.
    location          varchar,
    topic             varchar,
    -- Which upload this set came from. The object key is built from it, so a set that is replaced is a new
    -- object rather than the same one overwritten - and a build that reads these rows and then fetches the
    -- object cannot see a set that is half replaced.
    revision          bigint                   not null,
    object_key        varchar                  not null,
    sha256            varchar(64)              not null,
    size_in_bytes     bigint                   not null,
    source_repository varchar                  not null,
    source_ref        varchar                  not null,
    source_revision   varchar                  not null,
    source_timestamp  timestamp with time zone not null,
    version           varchar,
    uploaded_at       timestamp with time zone not null
);

-- What identifies a set, and therefore what a further upload replaces. In PostgreSQL two null values would
-- not conflict, so the three nullable columns are compared as empty ones - the same way
-- documentation_subject_identity folds a missing name.
create unique index custom_set_identity
    on custom_set (site, kind, system_name, coalesce(name, ''), source_format, template,
                   coalesce(location, ''), coalesce(topic, ''));

-- What a build reads: everything documented for one system of one site. It leads with the site, so it also
-- serves what the site partition reads - the subjects of a site, to add the ones nobody has deployed yet to
-- its parts.
create index custom_set_system on custom_set (site, system_name);

create table custom_page
(
    set_id    bigint  not null references custom_set (id) on delete cascade,
    -- The chapter folder the file lies in, as the uploaded archive names it.
    chapter   varchar not null,
    file_name varchar not null,
    -- Null for an asset, and for a page whose front matter carries no title.
    title     varchar,
    sidebar_position integer not null,
    asset     boolean not null,
    primary key (set_id, chapter, file_name)
);
