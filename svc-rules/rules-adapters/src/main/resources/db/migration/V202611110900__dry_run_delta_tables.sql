-- INV-T-19902 (INV-T-4974 slice 2; INV-REQ-772, INV-Q-14646 A): the stored dry-run delta as relational rows, one per
-- changed document and one per total, and the reason of a run that ends SAMPLE_UNAVAILABLE. No document-typed column.

-- 1. The reason of an unavailable run: set exactly when the state is SAMPLE_UNAVAILABLE, and never blank.
alter table rules.dry_run_delta add column unavailable_reason text null;
alter table rules.dry_run_delta add constraint dry_run_delta_unavailable_reason
    check ((state = 'SAMPLE_UNAVAILABLE') = (unavailable_reason is not null));
alter table rules.dry_run_delta add constraint dry_run_delta_unavailable_reason_text
    check (unavailable_reason is null or unavailable_reason !~ '^\s*$');

-- 2. rules.dry_run_delta_document: one row per changed document.
create table rules.dry_run_delta_document (
    tenant_id              uuid          not null,
    delta_id               uuid          not null references rules.dry_run_delta (id),
    position               integer       not null check (position >= 1),
    doc_uuid               uuid          not null,
    period_ref             varchar(32)   not null check (period_ref !~ '^\s*$'),
    entity_ref             text          null check (entity_ref is null or entity_ref !~ '^\s*$'),
    transaction_date       date          not null,
    preceding_document_date date         null,
    document_function      text          not null check (document_function !~ '^\s*$'),
    supply_type            text          null,
    change_type            text          not null check (change_type in ('CODE_CHANGED', 'NEWLY_REFUSED', 'NEWLY_ACCEPTED')),
    old_code               text          not null check (old_code !~ '^\s*$'),
    old_severity           text          null,
    new_code               text          not null check (new_code !~ '^\s*$'),
    new_severity           text          null,
    taxable_value          numeric(24, 2) not null,
    gst_value              numeric(24, 2) not null,
    values_absent          boolean       not null,
    straddling_credit_note boolean       not null,
    primary key (delta_id, position),
    unique (delta_id, doc_uuid),
    check (not values_absent or (taxable_value = 0 and gst_value = 0))
);

-- 3. rules.dry_run_delta_total: one row per item of changeTypeTotals (kind CHANGE_TYPE) or byPeriod (kind PERIOD).
create table rules.dry_run_delta_total (
    tenant_id           uuid           not null,
    delta_id            uuid           not null references rules.dry_run_delta (id),
    kind                text           not null check (kind in ('CHANGE_TYPE', 'PERIOD')),
    key                 text           not null check (key !~ '^\s*$'),
    position            integer        not null check (position >= 1),
    document_count      integer        not null check (document_count >= 0),
    values_absent_count integer        not null check (values_absent_count >= 0 and values_absent_count <= document_count),
    taxable_value       numeric(24, 2) not null,
    gst_value           numeric(24, 2) not null,
    primary key (delta_id, kind, position),
    unique (delta_id, kind, key)
);

alter table rules.dry_run_delta_document enable row level security;
alter table rules.dry_run_delta_document force row level security;
alter table rules.dry_run_delta_total enable row level security;
alter table rules.dry_run_delta_total force row level security;

create policy dry_run_delta_document_tenant_read on rules.dry_run_delta_document for select
    using (tenant_id = (select rules.bound_tenant()));
create policy dry_run_delta_document_tenant_insert on rules.dry_run_delta_document for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy dry_run_delta_total_tenant_read on rules.dry_run_delta_total for select
    using (tenant_id = (select rules.bound_tenant()));
create policy dry_run_delta_total_tenant_insert on rules.dry_run_delta_total for insert
    with check (tenant_id = (select rules.bound_tenant()));

-- The rows are written once with their COMPLETE run: insert and select only, nothing for PUBLIC.
revoke all on rules.dry_run_delta_document, rules.dry_run_delta_total from public;
grant select, insert on rules.dry_run_delta_document to rules_runtime;
grant select, insert on rules.dry_run_delta_total to rules_runtime;
