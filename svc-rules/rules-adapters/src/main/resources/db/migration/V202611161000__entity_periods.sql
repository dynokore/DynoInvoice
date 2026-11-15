-- INV-T-20226 (INV-REQ-1303, INV-REQ-772, INV-AC-20223): the read model of the period records tenancy-svc published as
-- PeriodRecordsChanged (event-carried state transfer). One row per tenant and entity holds the version of the set,
-- one row per period record holds its span. Relational columns only: no json, jsonb or bytea. The tables keep no
-- transaction fact, no document and no personal data. rules-svc computes no period; it stores what the event carries.

create table rules.entity_period_set (
    tenant_id   uuid        not null,
    entity_ref  text        not null check (entity_ref !~ '^\s*$' and char_length(entity_ref) <= 64),
    version     bigint      not null check (version >= 1),
    received_at timestamptz not null,
    primary key (tenant_id, entity_ref)
);

create table rules.entity_period (
    tenant_id    uuid not null,
    entity_ref   text not null,
    period_ref   text not null check (period_ref !~ '^\s*$' and char_length(period_ref) <= 64),
    period_start date not null,
    period_end   date not null,
    check (period_end >= period_start),
    primary key (tenant_id, entity_ref, period_ref),
    foreign key (tenant_id, entity_ref)
        references rules.entity_period_set (tenant_id, entity_ref) on delete cascade
);

alter table rules.entity_period_set enable row level security;
alter table rules.entity_period_set force row level security;
alter table rules.entity_period enable row level security;
alter table rules.entity_period force row level security;

create policy entity_period_set_tenant_read on rules.entity_period_set for select
    using (tenant_id = (select rules.bound_tenant()));
create policy entity_period_set_tenant_insert on rules.entity_period_set for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy entity_period_set_tenant_update on rules.entity_period_set for update
    using (tenant_id = (select rules.bound_tenant()))
    with check (tenant_id = (select rules.bound_tenant()));
create policy entity_period_set_tenant_delete on rules.entity_period_set for delete
    using (tenant_id = (select rules.bound_tenant()));
create policy entity_period_tenant_read on rules.entity_period for select
    using (tenant_id = (select rules.bound_tenant()));
create policy entity_period_tenant_insert on rules.entity_period for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy entity_period_tenant_update on rules.entity_period for update
    using (tenant_id = (select rules.bound_tenant()))
    with check (tenant_id = (select rules.bound_tenant()));
create policy entity_period_tenant_delete on rules.entity_period for delete
    using (tenant_id = (select rules.bound_tenant()));

revoke all on rules.entity_period_set, rules.entity_period from public;
grant select, insert, update, delete on rules.entity_period_set to rules_runtime;
grant select, insert, update, delete on rules.entity_period to rules_runtime;
