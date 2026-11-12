-- Where a page or a microsite asked to go among the other items of its chapter: a position among the items
-- with no reference of their own, or a reference placing it directly before or after one named item. All
-- three are nullable, and null keeps a set's or a page's order exactly as it was before this existed.
alter table custom_page
    add column order_position double precision,
    add column order_direction varchar,
    add column order_reference varchar;

alter table custom_set
    add column order_position double precision,
    add column order_direction varchar,
    add column order_reference varchar;

-- The same three, named like the upload parameters they came from rather than like the hint they will become:
-- an HTML microsite has no front matter of its own to read a hint out of, so its placement is these three
-- parameters of the upload that is recorded with it - see DocumentationUploadDescriptor. A retry has to read
-- back the same descriptor it sent, or every retry of a microsite naming one of these would look like a
-- different upload.
alter table documentation_upload
    add column sidebar_position double precision,
    add column sidebar_after varchar,
    add column sidebar_before varchar;
