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

-- What the publication gauges read: the builds of one publication, per site. Partial, because most rows of a
-- large site are single-part builds an upload asked for and those carry no publication at all.
create index documentation_build_publication on documentation_build (site, publication_id)
    where publication_id is not null;
