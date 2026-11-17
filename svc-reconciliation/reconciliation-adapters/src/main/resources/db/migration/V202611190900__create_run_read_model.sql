-- V202611190900__create_run_read_model.sql (INV-T-24344, INV-REQ-631, INV-REQ-1206, INV-AC-24341).
--
-- The run read model of reconciliation-svc, built from CanonicalVersionCreated, PeriodReassigned and
-- SubmissionAcknowledged (event-carried state transfer; no read of another service's schema). Tenant isolation is the
-- forced row-level security of every table of the schema (CS-19). What was received is append-only (NIST SP 800-53
-- AU-9): a trigger refuses UPDATE and DELETE of a version, line or acknowledgement row. Only document_period moves.
-- Money is exact decimal (numeric), never a binary float.
create table reconciliation.document_version (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version int not null check (version > 0),
    entity_ref text not null check (btrim(entity_ref) <> ''),
    transaction_date date,
    scope_verdict text,
    not_submittable_reason text,
    direction text not null check (direction in ('SALE', 'PURCHASE')),
    currency text,
    exchange_rate numeric,
    sgd_total_excluding_tax numeric,
    sgd_total_tax numeric,
    received_at timestamptz,
    primary key (tenant_id, doc_uuid, version)
);
create table reconciliation.document_line (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version int not null,
    line_number int not null,
    tax_code text,
    line_amount numeric,
    tax_amount numeric,
    primary key (tenant_id, doc_uuid, version, line_number),
    foreign key (tenant_id, doc_uuid, version) references reconciliation.document_version (tenant_id, doc_uuid, version)
);
create table reconciliation.document_period (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    entity_ref text not null check (btrim(entity_ref) <> ''),
    period_ref text not null check (btrim(period_ref) <> ''),
    docsequence bigint not null,
    primary key (tenant_id, doc_uuid)
);
create index document_period_by_period on reconciliation.document_period (tenant_id, entity_ref, period_ref);
-- no foreign key to document_version: the acknowledgement topic and the version topic are not ordered against each other
create table reconciliation.document_acknowledgement (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version int not null,
    acknowledgement_id text not null check (btrim(acknowledgement_id) <> ''),
    acknowledged_at timestamptz not null,
    primary key (tenant_id, doc_uuid, version)
);

comment on column reconciliation.document_version.entity_ref is 'class: operational';
comment on column reconciliation.document_version.transaction_date is 'class: operational';
comment on column reconciliation.document_version.scope_verdict is 'class: operational';
comment on column reconciliation.document_version.not_submittable_reason is 'class: operational';
comment on column reconciliation.document_version.direction is 'class: operational';
comment on column reconciliation.document_version.currency is 'class: operational';
comment on column reconciliation.document_version.exchange_rate is 'class: operational';
comment on column reconciliation.document_version.sgd_total_excluding_tax is 'class: operational';
comment on column reconciliation.document_version.sgd_total_tax is 'class: operational';
comment on column reconciliation.document_version.received_at is 'class: operational';
comment on column reconciliation.document_line.tax_code is 'class: operational';
comment on column reconciliation.document_line.line_amount is 'class: operational';
comment on column reconciliation.document_line.tax_amount is 'class: operational';
comment on column reconciliation.document_period.entity_ref is 'class: operational';
comment on column reconciliation.document_period.period_ref is 'class: operational';
comment on column reconciliation.document_acknowledgement.acknowledgement_id is 'class: operational';

ALTER TABLE reconciliation.document_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.document_version FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_tenant_isolation ON reconciliation.document_version
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
ALTER TABLE reconciliation.document_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.document_line FORCE ROW LEVEL SECURITY;
CREATE POLICY document_line_tenant_isolation ON reconciliation.document_line
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
ALTER TABLE reconciliation.document_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.document_period FORCE ROW LEVEL SECURITY;
CREATE POLICY document_period_tenant_isolation ON reconciliation.document_period
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
ALTER TABLE reconciliation.document_acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.document_acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY document_acknowledgement_tenant_isolation ON reconciliation.document_acknowledgement
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));

create function reconciliation.run_read_model_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a received read-model row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.run_read_model_append_only() from public;
create trigger document_version_append_only before update or delete on reconciliation.document_version
    for each row execute function reconciliation.run_read_model_append_only();
create trigger document_line_append_only before update or delete on reconciliation.document_line
    for each row execute function reconciliation.run_read_model_append_only();
create trigger document_acknowledgement_append_only before update or delete on reconciliation.document_acknowledgement
    for each row execute function reconciliation.run_read_model_append_only();

revoke update, delete, truncate on reconciliation.document_version from reconciliation_runtime;
revoke update, delete, truncate on reconciliation.document_line from reconciliation_runtime;
revoke update, delete, truncate on reconciliation.document_acknowledgement from reconciliation_runtime;
revoke delete, truncate on reconciliation.document_period from reconciliation_runtime;
grant select, insert on reconciliation.document_version to reconciliation_runtime;
grant select, insert on reconciliation.document_line to reconciliation_runtime;
grant select, insert on reconciliation.document_acknowledgement to reconciliation_runtime;
grant select, insert, update on reconciliation.document_period to reconciliation_runtime;
