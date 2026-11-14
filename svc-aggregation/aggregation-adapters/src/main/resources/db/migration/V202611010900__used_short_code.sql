-- V202611010900__used_short_code.sql (INV-T-11280, INV-Q-11266 A): the short codes already used in an aggregate number.
-- One row per tenant and code, written by the store of the first aggregate that carries the code; the row is what makes
-- the ShortCodeUsed event once-only (INV-AC-2779).
create table aggregation.used_short_code (
    tenant_id uuid not null,
    code text not null check (code ~ '^[A-Z0-9]{1,10}$'),
    node_kind text not null check (node_kind in ('ENTITY', 'BUSINESS_UNIT')),
    first_aggregate_id text not null,
    first_used_at timestamptz not null,
    primary key (tenant_id, code)
);
alter table aggregation.used_short_code enable row level security;
alter table aggregation.used_short_code force row level security;
create policy used_short_code_tenant_isolation on aggregation.used_short_code
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert on aggregation.used_short_code to aggregation_runtime;
