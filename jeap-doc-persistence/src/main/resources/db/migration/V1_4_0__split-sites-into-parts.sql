-- A site is published as several Docusaurus builds instead of one: a part per system, and a shell part for the
-- site's own pages and whatever no other part claims. Everything a build and a publication hang on therefore
-- gains the part it belongs to.
--
-- The default is the shell on purpose. The rows that exist were builds of a whole site, and the shell is the
-- part that serves whatever no other part claims - so the site published before this migration goes on being
-- served, whole, until the parts have been built. That is what makes the rollout free of a gap.

alter table documentation_build
    add column part varchar not null default 'shell',
    -- What the part's content hashed to. A build whose content hashes to what is published is not run at all,
    -- which is what makes a part per system affordable.
    add column content_digest varchar;

-- What is published: the newest succeeded build of one part. The index on (site, state, id) stays beside it:
-- it answers when a part of this site was last published, which an index led by the part cannot serve.
create index documentation_build_part_state_id on documentation_build (site, part, state, id desc);

-- The standing request is per part now, so a burst of uploads for one system is one build of one part - and two
-- systems changing at once are two builds rather than one of the site.
alter table documentation_build_request
    add column part varchar not null default 'shell';
alter table documentation_build_request
    drop constraint documentation_build_request_pkey;
alter table documentation_build_request
    add constraint documentation_build_request_pkey primary key (site, part);

-- A lock is named after what it locks, and what it locks is a part now:
-- documentationBuild-<site>/system-<slug>. Both halves are unbounded - a site id is configuration, a system
-- slug comes from the architecture repository - and the 64 characters ShedLock's example schema uses ran out
-- at about a twenty-character system name, on a name that is inserted rather than trimmed. So the column is
-- as unbounded as everything else here. Widening a varchar rewrites no row; it does rebuild the primary-key
-- index, which on a two row table costs nothing.
alter table shedlock
    alter column name type varchar;
