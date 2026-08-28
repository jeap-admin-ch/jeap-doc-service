-- The history of one site, newest first, as the administration API reads it. The index the publication uses
-- carries the state between the site and the id, so it cannot order a query that asks for every state of a
-- site - which is what a history is.
create index documentation_build_site_id on documentation_build (site, id desc);
