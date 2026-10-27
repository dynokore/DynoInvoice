-- V2__aggregate_intake.sql (INV-T-8473): the aggregates of the intake and their numbering sequence.
-- Identity (INV-REQ-434 item 1): entity, business unit, type, period and sequence. Replay key (item 2): the intake key
-- (entity_ref, source_key, source_revision) within the tenant. The two short codes the document number was built from
-- are kept (INV-REQ-436 item 3), so a replay answers the stored number whatever the structure replica holds now.
create sequence aggregation.aggregate_sequence;
create table aggregation.aggregate (
    aggregate_id uuid primary key,
    tenant_id uuid not null,
    entity_ref text not null,
    business_unit_ref text not null,
    aggregate_type text not null check (aggregate_type in ('POS', 'STI', 'PCP')),
    period_start date not null,
    period_end date not null,
    sequence bigint not null,
    source_key text not null,
    source_revision bigint not null,
    entity_code text not null check (entity_code ~ '^[A-Z0-9]{1,10}$'),
    business_unit_code text not null check (business_unit_code ~ '^[A-Z0-9]{1,10}$'),
    document_number text not null check (char_length(document_number) <= 64),
    issue_date date not null,
    transaction_date date not null,
    created_at timestamptz not null default now(),
    constraint aggregate_intake_key unique (tenant_id, entity_ref, source_key, source_revision),
    constraint aggregate_document_number_key unique (tenant_id, document_number),
    constraint aggregate_period_order check (period_start <= period_end)
);
alter table aggregation.aggregate enable row level security;
alter table aggregation.aggregate force row level security;
create policy aggregate_tenant_isolation on aggregation.aggregate
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert on aggregation.aggregate to aggregation_runtime;
grant usage on sequence aggregation.aggregate_sequence to aggregation_runtime;
