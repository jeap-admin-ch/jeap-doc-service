-- Full environment snapshots are the sole source for viewers and generated pages.
-- Do not synthesize a full graph from independently fetched subject snapshots or reuse their ETags.
-- Existing FULL_REACTIONS bytes, source-format tags and successful-import state stay intact.
delete from reaction_graph
where kind in ('SYSTEM_REACTIONS', 'COMPONENT_REACTIONS', 'MESSAGE_REACTIONS');

delete from architecture_import
where kind in ('SYSTEM_REACTIONS', 'COMPONENT_REACTIONS', 'MESSAGE_REACTIONS');
