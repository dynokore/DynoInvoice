-- V202611191300__create_work_paper_annotation.sql (INV-T-24380, INV-REQ-633, INV-REQ-1010, INV-AC-24379).
--
-- The append-only annotation and attestation evidence of the work-paper. An annotation is a new revision of
-- (tenant, run, box); the stored run is never changed. An attestation is one row per (tenant, run). Neither table is ever
-- updated or deleted (NIST SP 800-53 AU-9): the trigger refuses both with 42501 and the runtime login holds only select
-- and insert. annotated_by, attested_by and the acknowledgement hold the acting user ID, never a role (INV-Q-10647).
-- Tenant isolation is the forced row-level security of every table of the schema.
create table reconciliation.work_paper_annotation (
    tenant_id uuid not null,
    run_id text not null,
    box text not null check (box <> ''),
    revision int not null check (revision > 0),
    variance_class text null,
    "references" text[] not null check (cardinality("references") > 0),
    annotated_by text not null check (btrim(annotated_by) <> ''),
    annotated_at timestamptz not null,
    primary key (tenant_id, run_id, box, revision)
);
create table reconciliation.work_paper_attestation (
    tenant_id uuid not null,
    run_id text not null,
    attested_by text not null check (btrim(attested_by) <> ''),
    attested_at timestamptz not null,
    sole_attester_acknowledged_by text null,
    sole_attester_acknowledged_on date null,
    primary key (tenant_id, run_id),
    check (
        (sole_attester_acknowledged_by is null and sole_attester_acknowledged_on is null)
        or (sole_attester_acknowledged_by is not null and btrim(sole_attester_acknowledged_by) <> ''
            and sole_attester_acknowledged_on is not null)
    )
);
comment on column reconciliation.work_paper_annotation.tenant_id is 'class: operational';
comment on column reconciliation.work_paper_annotation.run_id is 'class: operational';
comment on column reconciliation.work_paper_annotation.box is 'class: operational';
comment on column reconciliation.work_paper_annotation.revision is 'class: operational';
comment on column reconciliation.work_paper_annotation.variance_class is 'class: operational';
comment on column reconciliation.work_paper_annotation."references" is 'class: operational';
comment on column reconciliation.work_paper_annotation.annotated_by is 'class: personal';
comment on column reconciliation.work_paper_annotation.annotated_at is 'class: operational';
comment on column reconciliation.work_paper_attestation.tenant_id is 'class: operational';
comment on column reconciliation.work_paper_attestation.run_id is 'class: operational';
comment on column reconciliation.work_paper_attestation.attested_by is 'class: personal';
comment on column reconciliation.work_paper_attestation.attested_at is 'class: operational';
comment on column reconciliation.work_paper_attestation.sole_attester_acknowledged_by is 'class: personal';
comment on column reconciliation.work_paper_attestation.sole_attester_acknowledged_on is 'class: operational';
ALTER TABLE reconciliation.work_paper_annotation ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.work_paper_annotation FORCE ROW LEVEL SECURITY;
CREATE POLICY work_paper_annotation_tenant_isolation ON reconciliation.work_paper_annotation
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.work_paper_annotation_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a work paper annotation row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.work_paper_annotation_append_only() from public;
create trigger work_paper_annotation_append_only before update or delete on reconciliation.work_paper_annotation
    for each row execute function reconciliation.work_paper_annotation_append_only();
revoke update, delete, truncate on reconciliation.work_paper_annotation from reconciliation_runtime;
grant select, insert on reconciliation.work_paper_annotation to reconciliation_runtime;
ALTER TABLE reconciliation.work_paper_attestation ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.work_paper_attestation FORCE ROW LEVEL SECURITY;
CREATE POLICY work_paper_attestation_tenant_isolation ON reconciliation.work_paper_attestation
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.work_paper_attestation_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a work paper attestation row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.work_paper_attestation_append_only() from public;
create trigger work_paper_attestation_append_only before update or delete on reconciliation.work_paper_attestation
    for each row execute function reconciliation.work_paper_attestation_append_only();
revoke update, delete, truncate on reconciliation.work_paper_attestation from reconciliation_runtime;
grant select, insert on reconciliation.work_paper_attestation to reconciliation_runtime;
