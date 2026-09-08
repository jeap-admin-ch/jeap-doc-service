-- A full publication of a site - every part of it, asked for at once by an operator or by the architecture
-- import - becomes a thing that can be named and therefore measured. Without it there is no answer to "how
-- long did publishing this documentation take": the parts are built on several instances, so no single one of
-- them knows when the last of them finished, and an instance that has restarted knows nothing at all.
--
-- Both columns are nullable, and null is the ordinary case for an upload: it asks for one part, and one part
-- is not a publication. The rows that exist were written before publications had names and stay null.

alter table documentation_build_request
    -- The identifier every part of one ask carries.
    add column publication_id varchar,
    -- When that ask was made - the same instant for every part of it. The parts are requested one statement at
    -- a time, so their own requested_at values are microseconds apart and the last one would not say when the
    -- publication began.
    add column publication_requested_at timestamptz;

alter table documentation_build
    add column publication_id varchar,
    add column publication_requested_at timestamptz;

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
