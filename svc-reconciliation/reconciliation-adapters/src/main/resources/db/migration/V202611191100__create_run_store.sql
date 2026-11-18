-- V202611191100__create_run_store.sql (INV-T-24376, INV-REQ-656, INV-AC-24375).
--
-- The append-only store of the completed reconciliation runs: one row per run in reconciliation_run and its Box,
-- classified-variance, unmapped-line and not-reconcilable rows in the child tables. "Earlier runs and their work-papers
-- remain, immutable" (INV-REQ-656 b3): the trigger of each table refuses update and delete with 42501, and the runtime
-- login holds only select and insert (NIST SP 800-53 AU-9). Tenant isolation is the forced row-level security of every
-- table of the schema. Money keeps scale 2.
create table reconciliation.reconciliation_run (
    tenant_id uuid not null,
    run_id uuid not null,
    entity_ref text not null check (btrim(entity_ref) <> ''),
    period_ref text not null check (btrim(period_ref) <> ''),
    return_version int not null check (return_version > 0),
    return_form text not null check (return_form in ('F5', 'F7', 'F8')),
    completed_at timestamptz not null,
    unexplained_residual numeric(19,2) not null,
    raises_per003 boolean not null,
    primary key (tenant_id, run_id)
);
create table reconciliation.reconciliation_run_box (
    tenant_id uuid not null,
    run_id uuid not null,
    box text not null check (btrim(box) <> ''),
    submitted_amount numeric(19,2) not null,
    filed_amount numeric(19,2) not null,
    variance numeric(19,2) not null,
    variance_class text,
    unexplained_residual numeric(19,2) not null,
    primary key (tenant_id, run_id, box)
);
create table reconciliation.reconciliation_run_variance (
    tenant_id uuid not null,
    run_id uuid not null,
    box text not null,
    category_code text not null check (btrim(category_code) <> ''),
    amount numeric(19,2) not null,
    linked_records text[] not null,
    primary key (tenant_id, run_id, box, category_code)
);
create table reconciliation.reconciliation_run_unmapped_line (
    tenant_id uuid not null,
    run_id uuid not null,
    doc_uuid text not null,
    version int not null check (version > 0),
    line_number int not null,
    tax_code text not null,
    primary key (tenant_id, run_id, doc_uuid, version, line_number, tax_code)
);
create table reconciliation.reconciliation_run_not_reconcilable (
    tenant_id uuid not null,
    run_id uuid not null,
    doc_uuid text not null,
    version int not null check (version > 0),
    reason text not null,
    primary key (tenant_id, run_id, doc_uuid, version, reason)
);
create index reconciliation_run_period_idx on reconciliation.reconciliation_run
    (tenant_id, entity_ref, period_ref, completed_at desc, run_id desc);
comment on column reconciliation.reconciliation_run.tenant_id is 'class: operational';
comment on column reconciliation.reconciliation_run.run_id is 'class: operational';
comment on column reconciliation.reconciliation_run.entity_ref is 'class: operational';
comment on column reconciliation.reconciliation_run.period_ref is 'class: operational';
comment on column reconciliation.reconciliation_run.return_version is 'class: operational';
comment on column reconciliation.reconciliation_run.return_form is 'class: operational';
comment on column reconciliation.reconciliation_run.completed_at is 'class: operational';
comment on column reconciliation.reconciliation_run.unexplained_residual is 'class: operational';
comment on column reconciliation.reconciliation_run.raises_per003 is 'class: operational';
comment on column reconciliation.reconciliation_run_box.tenant_id is 'class: operational';
comment on column reconciliation.reconciliation_run_box.run_id is 'class: operational';
comment on column reconciliation.reconciliation_run_box.box is 'class: operational';
comment on column reconciliation.reconciliation_run_box.submitted_amount is 'class: operational';
comment on column reconciliation.reconciliation_run_box.filed_amount is 'class: operational';
comment on column reconciliation.reconciliation_run_box.variance is 'class: operational';
comment on column reconciliation.reconciliation_run_box.variance_class is 'class: operational';
comment on column reconciliation.reconciliation_run_box.unexplained_residual is 'class: operational';
comment on column reconciliation.reconciliation_run_variance.tenant_id is 'class: operational';
comment on column reconciliation.reconciliation_run_variance.run_id is 'class: operational';
comment on column reconciliation.reconciliation_run_variance.box is 'class: operational';
comment on column reconciliation.reconciliation_run_variance.category_code is 'class: operational';
comment on column reconciliation.reconciliation_run_variance.amount is 'class: operational';
comment on column reconciliation.reconciliation_run_variance.linked_records is 'class: operational';
comment on column reconciliation.reconciliation_run_unmapped_line.tenant_id is 'class: operational';
comment on column reconciliation.reconciliation_run_unmapped_line.run_id is 'class: operational';
comment on column reconciliation.reconciliation_run_unmapped_line.doc_uuid is 'class: operational';
comment on column reconciliation.reconciliation_run_unmapped_line.version is 'class: operational';
comment on column reconciliation.reconciliation_run_unmapped_line.line_number is 'class: operational';
comment on column reconciliation.reconciliation_run_unmapped_line.tax_code is 'class: operational';
comment on column reconciliation.reconciliation_run_not_reconcilable.tenant_id is 'class: operational';
comment on column reconciliation.reconciliation_run_not_reconcilable.run_id is 'class: operational';
comment on column reconciliation.reconciliation_run_not_reconcilable.doc_uuid is 'class: operational';
comment on column reconciliation.reconciliation_run_not_reconcilable.version is 'class: operational';
comment on column reconciliation.reconciliation_run_not_reconcilable.reason is 'class: operational';
ALTER TABLE reconciliation.reconciliation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.reconciliation_run FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_run_tenant_isolation ON reconciliation.reconciliation_run
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.reconciliation_run_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a reconciliation run row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.reconciliation_run_append_only() from public;
create trigger reconciliation_run_append_only before update or delete on reconciliation.reconciliation_run
    for each row execute function reconciliation.reconciliation_run_append_only();
revoke update, delete, truncate on reconciliation.reconciliation_run from reconciliation_runtime;
grant select, insert on reconciliation.reconciliation_run to reconciliation_runtime;
ALTER TABLE reconciliation.reconciliation_run_box ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.reconciliation_run_box FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_run_box_tenant_isolation ON reconciliation.reconciliation_run_box
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.reconciliation_run_box_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a reconciliation run row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.reconciliation_run_box_append_only() from public;
create trigger reconciliation_run_box_append_only before update or delete on reconciliation.reconciliation_run_box
    for each row execute function reconciliation.reconciliation_run_box_append_only();
revoke update, delete, truncate on reconciliation.reconciliation_run_box from reconciliation_runtime;
grant select, insert on reconciliation.reconciliation_run_box to reconciliation_runtime;
ALTER TABLE reconciliation.reconciliation_run_variance ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.reconciliation_run_variance FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_run_variance_tenant_isolation ON reconciliation.reconciliation_run_variance
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.reconciliation_run_variance_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a reconciliation run row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.reconciliation_run_variance_append_only() from public;
create trigger reconciliation_run_variance_append_only before update or delete on reconciliation.reconciliation_run_variance
    for each row execute function reconciliation.reconciliation_run_variance_append_only();
revoke update, delete, truncate on reconciliation.reconciliation_run_variance from reconciliation_runtime;
grant select, insert on reconciliation.reconciliation_run_variance to reconciliation_runtime;
ALTER TABLE reconciliation.reconciliation_run_unmapped_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.reconciliation_run_unmapped_line FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_run_unmapped_line_tenant_isolation ON reconciliation.reconciliation_run_unmapped_line
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.reconciliation_run_unmapped_line_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a reconciliation run row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.reconciliation_run_unmapped_line_append_only() from public;
create trigger reconciliation_run_unmapped_line_append_only before update or delete on reconciliation.reconciliation_run_unmapped_line
    for each row execute function reconciliation.reconciliation_run_unmapped_line_append_only();
revoke update, delete, truncate on reconciliation.reconciliation_run_unmapped_line from reconciliation_runtime;
grant select, insert on reconciliation.reconciliation_run_unmapped_line to reconciliation_runtime;
ALTER TABLE reconciliation.reconciliation_run_not_reconcilable ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.reconciliation_run_not_reconcilable FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_run_not_reconcilable_tenant_isolation ON reconciliation.reconciliation_run_not_reconcilable
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.reconciliation_run_not_reconcilable_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a reconciliation run row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.reconciliation_run_not_reconcilable_append_only() from public;
create trigger reconciliation_run_not_reconcilable_append_only before update or delete on reconciliation.reconciliation_run_not_reconcilable
    for each row execute function reconciliation.reconciliation_run_not_reconcilable_append_only();
revoke update, delete, truncate on reconciliation.reconciliation_run_not_reconcilable from reconciliation_runtime;
grant select, insert on reconciliation.reconciliation_run_not_reconcilable to reconciliation_runtime;
