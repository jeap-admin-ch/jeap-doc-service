-- Full environment snapshots are the sole source for viewers and generated pages.
-- Do not synthesize a full graph from independently fetched subject snapshots or reuse their ETags.
-- Upgrade from 4.4.0 subject snapshots to the full snapshots introduced in 4.5.0.
delete from reaction_graph
where kind in ('SYSTEM_REACTIONS', 'COMPONENT_REACTIONS', 'MESSAGE_REACTIONS');

delete from architecture_import
where kind in ('SYSTEM_REACTIONS', 'COMPONENT_REACTIONS', 'MESSAGE_REACTIONS');

-- Subject names are no longer needed; full snapshots carry their API format instead.
alter table reaction_graph rename column upstream_name to source_format;
