-- A static site's tree is uploaded for the site itself: its subject, its upload and its set name no system, and
-- the set follows no structure template. Only that kind of subject may leave them out.
alter table documentation_subject alter column system_name drop not null;
alter table documentation_subject add constraint documentation_subject_system_present
    check (system_name is not null or kind = 'SITE');

alter table documentation_upload alter column template drop not null;

alter table custom_set alter column system_name drop not null;
alter table custom_set alter column template drop not null;
alter table custom_set add constraint custom_set_system_and_template_present
    check ((system_name is not null and template is not null) or kind = 'SITE');

-- The identities fold a missing system and template the way they already fold a missing name: two null values
-- would not conflict in PostgreSQL, and a second upload of a site's tree would add a set instead of replacing it.
drop index documentation_subject_identity;
create unique index documentation_subject_identity
    on documentation_subject (site, kind, coalesce(system_name, ''), coalesce(name, ''));

drop index custom_set_identity;
create unique index custom_set_identity
    on custom_set (site, kind, coalesce(system_name, ''), coalesce(name, ''), source_format,
                   coalesce(template, ''), coalesce(location, ''), coalesce(topic, ''));
