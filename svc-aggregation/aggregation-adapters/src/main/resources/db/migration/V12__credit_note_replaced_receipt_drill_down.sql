-- Credit-note aggregates (INV-REQ-438), the receipts a full tax invoice replaced (INV-REQ-440) and the retained
-- detail behind the drill-down (INV-REQ-414, INV-REQ-884; INV-T-4980).

alter table aggregation.aggregate
    add column credit_note boolean not null default false;

-- The retained underlying transaction list: the detail of one transaction kept for audit. `claimant_reference`
-- holds the reference of an expense claimant and never a name (data minimisation, INV-REQ-884).
alter table aggregation.aggregate_transaction
    add column credit_note boolean not null default false,
    add column underlying_ref text not null default '',
    add column claimant_reference text;

-- The credit-note aggregate of a closed window part sits beside the sales aggregate the part row names.
alter table aggregation.aggregate_window_part
    add column credit_note_aggregate_id uuid references aggregation.aggregate (aggregate_id);

grant update (credit_note_aggregate_id) on aggregation.aggregate_window_part to aggregation_runtime;
-- An exclusion rewrites the lines of an aggregate that was not transmitted.
grant delete on aggregation.aggregate_line to aggregation_runtime;

create table aggregation.replaced_receipt (
    tenant_id uuid not null,
    entity_ref text not null,
    receipt_number text not null,
    outcome text not null check (outcome in ('EXCLUDED', 'NOT_AGGREGATED')),
    recorded_at timestamptz not null default now(),
    primary key (tenant_id, entity_ref, receipt_number)
);
alter table aggregation.replaced_receipt enable row level security;
alter table aggregation.replaced_receipt force row level security;
create policy replaced_receipt_tenant_isolation on aggregation.replaced_receipt
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert on aggregation.replaced_receipt to aggregation_runtime;
