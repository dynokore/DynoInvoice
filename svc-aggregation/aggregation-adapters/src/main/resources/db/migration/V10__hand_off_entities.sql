-- V10__hand_off_entities.sql (INV-T-4979; INV-REQ-718 item 2, INV-REQ-439): the (tenant, entity) pairs the hand-off job
-- runs for. An aggregate that is due for hand-off (for example a supplementary over a transmitted aggregate) may belong
-- to an entity that holds no open window, so open_window_entities() of V8 is not enough: this function returns the
-- pairs with an open window and the pairs with an aggregate not yet handed on and not superseded. SECURITY DEFINER,
-- owned by the NOLOGIN aggregation_sys, a fixed search_path, key columns only (TPL-09); aggregation_sys reads the
-- aggregate table through a select policy and a grant on the four columns it needs, never on a content column.
-- The job asks the database for pairs only; which aggregates are due is decided per entity by the hand-off use case.
create policy aggregate_sys_select on aggregation.aggregate for select to aggregation_sys using (true);
grant select (tenant_id, entity_ref, superseded_by, handed_on_at) on aggregation.aggregate to aggregation_sys;
create function aggregation.hand_off_entities() returns table (tenant_id uuid, entity_ref text)
language sql stable security definer set search_path = pg_catalog, aggregation, pg_temp as $$
    select w.tenant_id, w.entity_ref from aggregation.aggregate_window w where w.state = 'OPEN'
    union
    select a.tenant_id, a.entity_ref from aggregation.aggregate a
    where a.superseded_by is null and a.handed_on_at is null
$$;
grant create on schema aggregation to aggregation_sys;
alter function aggregation.hand_off_entities() owner to aggregation_sys;
revoke all on function aggregation.hand_off_entities() from public;
grant execute on function aggregation.hand_off_entities() to aggregation_runtime;
revoke create on schema aggregation from aggregation_sys;
