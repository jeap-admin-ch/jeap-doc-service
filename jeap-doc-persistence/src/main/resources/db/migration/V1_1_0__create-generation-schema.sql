-- The standing request to publish a site: at most one row per site, whatever asked how often. Several triggers
-- arriving while a build runs therefore set the same flag and are one request, which is what makes a burst of
-- uploads produce exactly one follow-up run.
create table documentation_build_request
(
    site         varchar                  not null primary key,
    requested_at timestamp with time zone not null,
    trigger_kind varchar                  not null
);

-- Incremented by one, which is what the allocation size of the entity expects - and the identifier of a build
-- is a path segment, of its workspace and of the objects its site is published under.
create sequence documentation_build_id_seq increment by 1;

-- What the documentation generator has run. It is the evidence an operator reads, and it is the publication
-- itself: the newest succeeded build of a site is the site being served.
create table documentation_build
(
    id                bigint                   not null primary key,
    site              varchar                  not null,
    trigger_kind      varchar                  not null,
    state             varchar                  not null,
    started_at        timestamp with time zone not null,
    finished_at       timestamp with time zone,
    instance          varchar                  not null,
    object_prefix     varchar,
    page_count        integer                  not null default 0,
    size_in_bytes     bigint                   not null default 0,
    docusaurus_millis bigint                   not null default 0,
    failure_reason    varchar
);

-- What is published: the newest succeeded build of a site, asked on every request that is not answered from the
-- cache, and asked again by the retention. Ordering is by id rather than by finished_at, because the id comes
-- from a sequence and is monotonic where two clocks are not.
create index documentation_build_site_state_id on documentation_build (site, state, id desc);
-- The clean-up of old records.
create index documentation_build_finished_at on documentation_build (finished_at);
-- Which builds are running, asked before every build to decide which workspaces may be removed. Partial, because
-- the answer is almost always a handful of rows out of a history kept for months.
create index documentation_build_running on documentation_build (id) where state = 'RUNNING';
