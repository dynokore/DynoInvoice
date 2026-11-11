-- V7__period_spans.sql (INV-T-16565; INV-REQ-435, INV-REQ-413, INV-REQ-1316): the read model of the effective-dated
-- period spans of an entity, fed by PeriodRecordsChanged (INV-Q-14444 A, INV-Q-16535 A). One row per entity holds the
-- last applied version (idempotent consumer by version); the period records of the entity are replaced as a whole when
-- a higher version arrives. The tenant is part of every key.
create table aggregation.period_span_version (
    tenant_id uuid not null,
    entity_ref text not null,
    version bigint not null check (version >= 1),
    updated_at timestamptz not null default now(),
    primary key (tenant_id, entity_ref)
);
alter table aggregation.period_span_version enable row level security;
alter table aggregation.period_span_version force row level security;
create policy period_span_version_tenant_isolation on aggregation.period_span_version
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert, update (version, updated_at) on aggregation.period_span_version to aggregation_runtime;

create table aggregation.period_span (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    period_start date not null,
    period_end date not null,
    primary key (tenant_id, entity_ref, period_ref),
    constraint period_span_order check (period_start <= period_end)
);
create index period_span_range on aggregation.period_span (tenant_id, entity_ref, period_start, period_end);
alter table aggregation.period_span enable row level security;
alter table aggregation.period_span force row level security;
create policy period_span_tenant_isolation on aggregation.period_span
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert, delete on aggregation.period_span to aggregation_runtime;
