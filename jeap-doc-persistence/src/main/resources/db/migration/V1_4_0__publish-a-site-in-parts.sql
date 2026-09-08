-- A site is published as several Docusaurus builds instead of one: a part per system, and a shell part for the
-- site's own pages and whatever no other part claims. Everything a build and a publication hang on therefore
-- gains the part it belongs to, a full publication of every part at once becomes a thing that can be named and
-- measured, and an ask that must not be skipped says so on the row.
--
-- The default part is the shell on purpose. The rows that exist were builds of a whole site, and the shell is
-- the part that serves whatever no other part claims - so the site published before this migration goes on
-- being served, whole, until the parts have been built. That is what makes the rollout free of a gap.

alter table documentation_build
    add column part varchar not null default 'shell',
    -- What the part's content hashed to. A build whose content hashes to what is published is not run at all,
    -- which is what makes a part per system affordable.
    add column content_digest varchar,
    -- The identifier every part of one full publication carries, and when that ask was made - the same instant
    -- for every part of it. Both nullable: null is the ordinary case for an upload, which asks for one part,
    -- and one part is not a publication. The parts are requested one statement at a time, so their own
    -- requested_at values are microseconds apart and the last one would not say when the publication began.
    add column publication_id varchar,
    add column publication_requested_at timestamptz;

-- What is published: the newest succeeded build of one part. The index on (site, state, id) stays beside it:
-- it answers when a part of this site was last published, which an index led by the part cannot serve.
create index documentation_build_part_state_id on documentation_build (site, part, state, id desc);

-- The standing request is per part now, so a burst of uploads for one system is one build of one part - and two
-- systems changing at once are two builds rather than one of the site.
alter table documentation_build_request
    add column part varchar not null default 'shell',
    add column publication_id varchar,
    add column publication_requested_at timestamptz,
    -- Whether the content digest may skip this build. A build somebody asked for by hand is never skipped -
    -- which is what the administration API promises - and that cannot be read off trigger_kind, because
    -- trigger_kind records who asked *first*: two asks for one part are one row, so a request already pending
    -- when an operator forces a publication would keep the earlier ask's trigger. On a site fed by the hourly
    -- architecture import parts are pending for a good part of every hour. So an ask that may not be skipped
    -- raises this flag on the row it finds, and trigger_kind goes on being the record of who asked first.
    add column forced boolean not null default false;
alter table documentation_build_request
    drop constraint documentation_build_request_pkey;
alter table documentation_build_request
    add constraint documentation_build_request_pkey primary key (site, part);

-- The three indexes the publication gauges are read through. All partial: most rows of a large site are
-- single-part builds an upload asked for, and those carry no publication at all.

-- The anti-joins that decide whether a publication is over filter on publication_id alone - they ask whether
-- *any* row of that publication is still running or still owed - so an index led by the site cannot serve
-- them.
create index documentation_build_publication on documentation_build (publication_id, site)
    where publication_id is not null;

-- The same anti-join over the pending requests. The table is small, but a sequential scan of it per
-- publication row of the group is not.
create index documentation_build_request_publication on documentation_build_request (publication_id)
    where publication_id is not null;

-- The outer half of the publication query, which the two above cannot serve: it reads the publications *of one
-- site* since an instant. Without this it falls back to (site, id desc) and walks the site's whole history
-- inside the ninety day retention - twice per scrape, for a one row answer.
create index documentation_build_site_publication
    on documentation_build (site, publication_requested_at desc)
    where publication_id is not null;

-- A lock is named after what it locks, and what it locks is a part now:
-- documentationBuild-<site>/system-<slug>. Both halves are unbounded - a site id is configuration, a system
-- slug comes from the architecture repository - and the 64 characters ShedLock's example schema uses ran out
-- at about a twenty-character system name, on a name that is inserted rather than trimmed. So the column is
-- as unbounded as everything else here. Widening a varchar rewrites no row; it does rebuild the primary-key
-- index, which on a two row table costs nothing.
alter table shedlock
    alter column name type varchar;
