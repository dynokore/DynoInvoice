-- V3__aggregation_windows.sql (INV-T-14462; INV-REQ-1316, INV-REQ-434): the aggregation windows of an entity, business
-- unit and category, and the placement that the first intake of an aggregate made in one of them. The window of an
-- aggregate is kept with the aggregate, so a replay answers the state of the first intake and never the current one.
create table aggregation.aggregate_window (
    window_id uuid primary key,
    tenant_id uuid not null,
    entity_ref text not null,
    business_unit_ref text not null,
    aggregate_type text not null check (aggregate_type in ('POS', 'STI', 'PCP')),
    cadence text not null check (cadence in ('DAILY', 'WEEKLY', 'MONTHLY')),
    window_start date not null,
    window_end date not null,
    state text not null default 'OPEN' check (state in ('OPEN', 'CLOSED', 'LATE')),
    created_at timestamptz not null default now(),
    constraint aggregate_window_key unique (tenant_id, entity_ref, business_unit_ref, aggregate_type, cadence, window_start),
    constraint aggregate_window_order check (window_start <= window_end)
);
create index aggregate_window_open on aggregation.aggregate_window (tenant_id, window_end) where state = 'OPEN';
alter table aggregation.aggregate_window enable row level security;
alter table aggregation.aggregate_window force row level security;
create policy aggregate_window_tenant_isolation on aggregation.aggregate_window
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert, update (state) on aggregation.aggregate_window to aggregation_runtime;
alter table aggregation.aggregate
    add column window_start date,
    add column window_end date,
    add column window_state text check (window_state in ('OPEN', 'CLOSED', 'LATE')),
    add constraint aggregate_window_placement check (
        (window_start is null) = (window_end is null) and (window_start is null) = (window_state is null)
        and (window_start is null or window_start <= window_end));
