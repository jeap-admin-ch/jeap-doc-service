-- Where a system or component a team uploaded documentation for stands in its deployment. An upload records
-- its system - and its component, for a component's documentation - as awaiting deployment when it is new; the
-- architecture import marks it deployed once it appears in the model, and undeployed once it has left the model
-- again, which is when its documentation is removed. A library has no row of its own: it is never in the model,
-- and goes with its system.

-- Incremented by one, which is what the allocation size of the entity expects.
create sequence custom_subject_deployment_id_seq increment by 1;

create table custom_subject_deployment
(
    id          bigint                   not null primary key,
    site        varchar                  not null,
    system_name varchar                  not null,
    -- The component, null for the system itself.
    component   varchar,
    -- AWAITING_DEPLOYMENT, DEPLOYED or UNDEPLOYED.
    state       varchar                  not null,
    changed_at  timestamp with time zone not null
);

-- One row per system and per component. A missing component is compared as an empty one, the same way
-- custom_set_identity folds a missing name: in PostgreSQL two null values would not conflict.
create unique index custom_subject_deployment_identity
    on custom_subject_deployment (site, system_name, coalesce(component, ''));

-- What is documented already starts out awaiting deployment. Nothing is removed because of this migration:
-- documentation is removed only once its subject has been seen deployed and is gone again.
insert into custom_subject_deployment (id, site, system_name, component, state, changed_at)
select nextval('custom_subject_deployment_id_seq'), site, system_name, component, 'AWAITING_DEPLOYMENT', now()
from (select distinct site, system_name, null::varchar as component
      from custom_set
      union
      select distinct site, system_name, name
      from custom_set
      where kind = 'COMPONENT') documented;
