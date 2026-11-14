SET lock_timeout = '5s';
-- V9__window_state_version.sql (INV-T-22212; INV-REQ-413): the producer-owned counter of AggregateWindowChanged. One row
-- per tenant, entity, prescribed period and window part; state_version starts at 1 and rises by one for every event the
-- outbox adapter appends for that key, in the transaction of the append.
create table aggregation.window_state_version (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    part_from date not null,
    part_to date not null,
    state_version bigint not null check (state_version >= 1),
    primary key (tenant_id, entity_ref, period_ref, part_from, part_to)
);
alter table aggregation.window_state_version enable row level security;
alter table aggregation.window_state_version force row level security;
create policy window_state_version_tenant_isolation on aggregation.window_state_version
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert, update on aggregation.window_state_version to aggregation_runtime;
