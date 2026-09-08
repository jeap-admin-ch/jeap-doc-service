-- The indexes the gauges are read through. Two of them were lost or never written when a site became several
-- parts, and every Prometheus scrape pays for it: these queries run per site, per scrape.

-- What "when was a part of this site last published, or found already current" is read by. The split replaced
-- (site, state, id) with (site, part, state, id) - which serves "what is published for this part" and not this
-- one: without a leading (site, state) the newest SUCCEEDED row of a site is found by walking (site, id desc)
-- back over every SKIPPED row, and skipping is now the ordinary outcome.
create index if not exists documentation_build_site_state_id on documentation_build (site, state, id desc);

-- The anti-joins that decide whether a publication is over filter on publication_id alone - they ask whether
-- *any* row of that publication is still running or still owed - so a partial index led by the site could not
-- serve them.
drop index if exists documentation_build_publication;
create index documentation_build_publication on documentation_build (publication_id, site)
    where publication_id is not null;

-- And the same anti-join over the pending requests, which had no index for it at all: the table is small, but
-- a sequential scan of it per publication row of the group is not.
create index if not exists documentation_build_request_publication on documentation_build_request (publication_id)
    where publication_id is not null;
