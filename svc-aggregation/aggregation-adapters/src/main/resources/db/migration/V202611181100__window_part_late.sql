SET lock_timeout = '5s';
-- V202611181100 (INV-T-24764; INV-REQ-413, INV-AC-24763): a window part that a transaction dated inside it reached after
-- it had closed is recorded LATE, once. One row per tenant, window and part; a second record of the same part inserts
-- nothing. The closed aggregate, its lines and its transaction rows are never written by this path.
create table aggregation.aggregate_window_part_late (
    tenant_id uuid not null,
    window_id uuid not null references aggregation.aggregate_window (window_id),
    part_start date not null,
    part_end date not null,
    recorded_at timestamptz not null default now(),
    primary key (tenant_id, window_id, part_start),
    constraint aggregate_window_part_late_order check (part_start <= part_end)
);
alter table aggregation.aggregate_window_part_late enable row level security;
alter table aggregation.aggregate_window_part_late force row level security;
create policy aggregate_window_part_late_tenant_isolation on aggregation.aggregate_window_part_late
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert on aggregation.aggregate_window_part_late to aggregation_runtime;
