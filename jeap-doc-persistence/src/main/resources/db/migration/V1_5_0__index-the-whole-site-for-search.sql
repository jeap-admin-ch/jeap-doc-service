-- The site has a search again, and its index is not part of a build.
--
-- A site is published as one Docusaurus build per part, so an index built inside a build would only ever cover
-- one part and a reader inside one system would search only that system. The index is therefore produced on its
-- own, over the whole site, and published like a part is: written under a prefix named after this row, and made
-- current by one update rather than by overwriting what is being read.
--
-- The rows of the indexes it replaces are kept for a while on purpose. Every file the indexer writes carries a
-- content hash in its name, so a reader who has loaded one index's manifest goes on asking for chunks that
-- exist only under that index's prefix - deleting the old one at the moment of the swap would answer their next
-- keystroke with a 404.

create sequence documentation_search_index_id_seq start with 1 increment by 1;

create table documentation_search_index
(
    id             bigint       not null primary key,
    site           varchar      not null,
    -- RUNNING, PUBLISHED or FAILED. The newest PUBLISHED one of a site is what that site is served from.
    state          varchar      not null,
    -- Where its files are, once there are any. Null while it runs and if it fails.
    object_prefix  varchar,
    -- How many pages went into it, which is what says an index covers the site rather than a corner of it.
    records        integer,
    -- Which instance built it, for a log line that has to be attributed.
    instance       varchar      not null,
    started_at     timestamptz  not null,
    finished_at    timestamptz,
    failure_reason text
);

-- What a site is served from, and what housekeeping walks: the newest published index per site, then the ones
-- it superseded. Both are the same descending scan.
create index documentation_search_index_site_state_id
    on documentation_search_index (site, state, id desc);
