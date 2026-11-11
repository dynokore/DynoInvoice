-- V6__aggregate_transactions.sql (INV-T-14463; INV-REQ-435, INV-REQ-434): the dated transactions of the transaction-level
-- intake and the parts in which a closed window was split at the prescribed-period boundaries. (The task text names
-- V5; V5 is taken by V5__outbox_testmode.sql on main, so this file takes the next version, coordinator rule G-MIG.)
-- A transaction is stored with the window of its own date and the state of that window at the intake, so a replay of
-- (entity_ref, source_key, source_revision) answers the original and stores nothing. aggregate_id is set when the part
-- of the window holding the transaction closes: the aggregate then holds exactly the transactions dated in its period.
create table aggregation.aggregate_transaction (
    tenant_id uuid not null,
    entity_ref text not null,
    business_unit_ref text not null,
    aggregate_type text not null check (aggregate_type in ('POS', 'STI', 'PCP')),
    source_key text not null,
    source_revision bigint not null,
    transaction_ref text not null,
    position integer not null check (position >= 0),
    transaction_date date not null,
    internal_tax_code text not null,
    amount text not null check (amount ~ '^-?[0-9]+(\.[0-9]+)?$'),
    window_start date not null,
    window_end date not null,
    window_state text not null check (window_state in ('OPEN', 'CLOSED', 'LATE')),
    aggregate_id uuid references aggregation.aggregate (aggregate_id),
    created_at timestamptz not null default now(),
    constraint aggregate_transaction_key unique (tenant_id, entity_ref, source_key, source_revision, transaction_ref),
    constraint aggregate_transaction_window check (window_start <= transaction_date and transaction_date <= window_end)
);
create index aggregate_transaction_window_idx on aggregation.aggregate_transaction
    (tenant_id, entity_ref, business_unit_ref, aggregate_type, window_start, window_end, transaction_date);
alter table aggregation.aggregate_transaction enable row level security;
alter table aggregation.aggregate_transaction force row level security;
create policy aggregate_transaction_tenant_isolation on aggregation.aggregate_transaction
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert, update (aggregate_id) on aggregation.aggregate_transaction to aggregation_runtime;

create table aggregation.aggregate_window_part (
    tenant_id uuid not null,
    window_id uuid not null references aggregation.aggregate_window (window_id),
    part_start date not null,
    part_end date not null,
    period_ref text not null,
    aggregate_id uuid not null references aggregation.aggregate (aggregate_id),
    closed_at timestamptz not null default now(),
    primary key (window_id, part_start),
    constraint aggregate_window_part_order check (part_start <= part_end)
);
alter table aggregation.aggregate_window_part enable row level security;
alter table aggregation.aggregate_window_part force row level security;
create policy aggregate_window_part_tenant_isolation on aggregation.aggregate_window_part
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert on aggregation.aggregate_window_part to aggregation_runtime;
