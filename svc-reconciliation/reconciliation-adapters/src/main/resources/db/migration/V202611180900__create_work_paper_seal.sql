-- V202611180900__create_work_paper_seal.sql (INV-T-24187, INV-REQ-631, INV-AC-24183; INV-Q-24164 A).
--
-- The append-only seal index of the sealed work-papers: one row per (tenant, run, seal version), naming the object-store
-- key of the sealed bytes and the SHA-256 of exactly those bytes. A row is never updated or deleted (NIST SP 800-53
-- AU-9): the trigger refuses both, and the runtime login holds only select and insert. Tenant isolation is the forced
-- row-level security of every table of the schema.
create table reconciliation.work_paper_seal (
    tenant_id uuid not null,
    run_id text not null,
    seal_version int not null check (seal_version > 0),
    entity_ref text not null,
    reference text not null,
    sha256 char(64) not null check (sha256 ~ '^[0-9a-f]{64}$'),
    sealed_at timestamptz not null,
    primary key (tenant_id, run_id, seal_version),
    unique (tenant_id, reference)
);
comment on column reconciliation.work_paper_seal.reference is 'class: operational';
comment on column reconciliation.work_paper_seal.sha256 is 'class: operational';
ALTER TABLE reconciliation.work_paper_seal ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.work_paper_seal FORCE ROW LEVEL SECURITY;
CREATE POLICY work_paper_seal_tenant_isolation ON reconciliation.work_paper_seal
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.work_paper_seal_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a work-paper seal row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.work_paper_seal_append_only() from public;
create trigger work_paper_seal_append_only before update or delete on reconciliation.work_paper_seal
    for each row execute function reconciliation.work_paper_seal_append_only();
revoke update, delete, truncate on reconciliation.work_paper_seal from reconciliation_runtime;
grant select, insert on reconciliation.work_paper_seal to reconciliation_runtime;
