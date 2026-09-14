-- The menu label of an HTML microsite, which the navigation and the page heading show. Null for markdown,
-- where every page carries its own title.
alter table custom_set
    add column label varchar;
