-- A build somebody asked for by hand is never skipped - which is what the administration API promises, and
-- what it could not keep. Whether the content digest is consulted was read off trigger_kind, and trigger_kind
-- records who asked *first*: a request already pending when an operator forces a publication keeps the trigger
-- of that earlier ask, because two asks for one part are one row. On a site fed by the hourly architecture
-- import parts are pending for a good part of every hour, so forcing a publication did nothing at all for them
-- - it answered 202, the parts were built, and every one of them was skipped by its digest.
--
-- So whether the digest may skip a build is its own column now, and trigger_kind goes on being the record of
-- who asked first. An ask that may not be skipped raises the flag on the row it finds, rather than trying to
-- overwrite what asked first.
--
-- Metadata-only on a populated table: a boolean with a non-null default is a catalogue change in PostgreSQL 11
-- and later, and no row is rewritten. The rows that exist were written before the flag and read as not forced,
-- which is what they were - a request pending across this deployment is served by the digest as it was.

alter table documentation_build_request
    add column forced boolean not null default false;
