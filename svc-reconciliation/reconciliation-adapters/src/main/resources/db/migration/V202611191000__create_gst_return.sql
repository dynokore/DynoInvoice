-- V202611191000__create_gst_return.sql (INV-T-24367, INV-REQ-653, INV-AC-24366).
--
-- The append-only store of the filed GST returns: one row per (tenant, entity, period, version) in gst_return and one
-- row per Box in gst_return_box. A version is immutable (INV-REQ-653 b3): the trigger of each table refuses update and
-- delete with 42501, and the runtime login holds only select and insert (NIST SP 800-53 AU-9). Tenant isolation is the
-- forced row-level security of every table of the schema. uploaded_by is the acting user ID of the uploading request,
-- never a role and never the free-text preparer.
create table reconciliation.gst_return (
    tenant_id uuid not null,
    entity_ref text not null check (btrim(entity_ref) <> ''),
    period_ref text not null check (btrim(period_ref) <> ''),
    version int not null check (version > 0),
    form text not null check (form in ('F5', 'F7', 'F8')),
    filed_at timestamptz not null,
    filing_acknowledgement text not null check (btrim(filing_acknowledgement) <> ''),
    filing_reference text,
    preparer text,
    uploaded_by text not null check (btrim(uploaded_by) <> ''),
    amends int check (amends is null or amends > 0),
    recorded_at timestamptz not null,
    primary key (tenant_id, entity_ref, period_ref, version)
);
create table reconciliation.gst_return_box (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    version int not null check (version > 0),
    box text not null check (btrim(box) <> ''),
    description text,
    amount numeric(19,2) not null,
    primary key (tenant_id, entity_ref, period_ref, version, box)
);
comment on column reconciliation.gst_return.tenant_id is 'class: operational';
comment on column reconciliation.gst_return.entity_ref is 'class: operational';
comment on column reconciliation.gst_return.period_ref is 'class: operational';
comment on column reconciliation.gst_return.version is 'class: operational';
comment on column reconciliation.gst_return.form is 'class: operational';
comment on column reconciliation.gst_return.filed_at is 'class: operational';
comment on column reconciliation.gst_return.filing_acknowledgement is 'class: operational';
comment on column reconciliation.gst_return.filing_reference is 'class: operational';
comment on column reconciliation.gst_return.preparer is 'class: personal';
comment on column reconciliation.gst_return.uploaded_by is 'class: personal';
comment on column reconciliation.gst_return.amends is 'class: operational';
comment on column reconciliation.gst_return.recorded_at is 'class: operational';
ALTER TABLE reconciliation.gst_return ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.gst_return FORCE ROW LEVEL SECURITY;
CREATE POLICY gst_return_tenant_isolation ON reconciliation.gst_return
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.gst_return_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a filed-return row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.gst_return_append_only() from public;
create trigger gst_return_append_only before update or delete on reconciliation.gst_return
    for each row execute function reconciliation.gst_return_append_only();
revoke update, delete, truncate on reconciliation.gst_return from reconciliation_runtime;
grant select, insert on reconciliation.gst_return to reconciliation_runtime;
comment on column reconciliation.gst_return_box.tenant_id is 'class: operational';
comment on column reconciliation.gst_return_box.entity_ref is 'class: operational';
comment on column reconciliation.gst_return_box.period_ref is 'class: operational';
comment on column reconciliation.gst_return_box.version is 'class: operational';
comment on column reconciliation.gst_return_box.box is 'class: operational';
comment on column reconciliation.gst_return_box.description is 'class: operational';
comment on column reconciliation.gst_return_box.amount is 'class: operational';
ALTER TABLE reconciliation.gst_return_box ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.gst_return_box FORCE ROW LEVEL SECURITY;
CREATE POLICY gst_return_box_tenant_isolation ON reconciliation.gst_return_box
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.gst_return_box_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a filed-return row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.gst_return_box_append_only() from public;
create trigger gst_return_box_append_only before update or delete on reconciliation.gst_return_box
    for each row execute function reconciliation.gst_return_box_append_only();
revoke update, delete, truncate on reconciliation.gst_return_box from reconciliation_runtime;
grant select, insert on reconciliation.gst_return_box to reconciliation_runtime;
