-- V7__evidence_integrity.sql (INV-T-7261; INV-Q-7251 A (4), INV-Q-7253 A (1)).
--
-- The run store and the failure store of the routine chain verification, then the sys-owned function that lists the
-- (tenant, entity) pairs the job runs for. Both stores are append-only and tenant-isolated the way the other evidence
-- tables are (the trailer of EvidenceLedgerSchema.trailerDdl: row-level security, REVOKE UPDATE/DELETE/TRUNCATE, the
-- append-only trigger, then the grant). An integrity_failure row is open for as long as it exists: this slice has no
-- closing row, no update and no delete (INV-Q-7253 A (1) and (5)). The unique index makes a repeat of the same
-- (chain key, kind, failing sequence) a conflict, so the code checks it first and appends nothing.

create table evidence.integrity_run (
    seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    run_id text not null,
    run_at timestamptz not null,
    result text not null check (result in ('VERIFIED', 'FAILED')),
    cause text not null check (cause in ('VERIFICATION', 'UNREADABLE')),
    chain_length bigint not null check (chain_length >= 0),
    period_digest text check (period_digest is null or period_digest ~ '^sha256:[0-9a-f]{64}$'),
    -- a chain or copy that could not be read is a FAILED run of length 0 with no digest (INV-Q-12988 A)
    check (cause = 'VERIFICATION' or (result = 'FAILED' and chain_length = 0 and period_digest is null)),
    unique (tenant_id, entity_ref, period_ref, run_id)
);
create index integrity_run_latest on evidence.integrity_run (tenant_id, entity_ref, period_ref, run_at desc, seq desc);

ALTER TABLE evidence.integrity_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.integrity_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.integrity_run
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.integrity_run FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.integrity_run_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER integrity_run_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.integrity_run
    FOR EACH ROW EXECUTE FUNCTION evidence.integrity_run_immutable();
grant select, insert on evidence.integrity_run to evidence_runtime;

create table evidence.integrity_failure (
    seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    evidence_type text not null check (evidence_type in ('PAYLOAD', 'ACKNOWLEDGEMENT', 'DECISION_TRACE', 'AUDIT_LOG')),
    kind text not null check (kind in ('BROKEN_CHAIN', 'DIGEST_MISMATCH', 'RETAINED_COPY_MISMATCH')),
    failing_seq bigint check (failing_seq is null or failing_seq >= 0),
    range_from timestamptz not null,
    range_to timestamptz not null check (range_to >= range_from),
    run_at timestamptz not null,
    run_id text not null,
    check ((kind = 'BROKEN_CHAIN') = (failing_seq is not null))
);
create unique index integrity_failure_open
    on evidence.integrity_failure (tenant_id, entity_ref, period_ref, evidence_type, kind, coalesce(failing_seq, -1));
create index integrity_failure_range on evidence.integrity_failure (tenant_id, range_from, range_to);

ALTER TABLE evidence.integrity_failure ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.integrity_failure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.integrity_failure
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.integrity_failure FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.integrity_failure_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER integrity_failure_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.integrity_failure
    FOR EACH ROW EXECUTE FUNCTION evidence.integrity_failure_immutable();
grant select, insert on evidence.integrity_failure to evidence_runtime;

-- The entity source of the job (TPL-09, ARC 4.3 step 1a): the runtime login reads the chain tables under its tenant
-- policy only, so the pairs come from a SECURITY DEFINER function owned by the NOLOGIN evidence_sys. It reads the two
-- key columns of two tables and returns key columns only, as one jsonb array (the shape of the job state's sys
-- functions, which the tenant-bound DSLContext does not add a tenant predicate to).
grant usage on schema evidence to evidence_sys;
create policy applied_record_sys_select on evidence.applied_record for select to evidence_sys using (true);
create policy period_digest_sys_select on evidence.period_digest for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.applied_record to evidence_sys;
grant select (tenant_id, entity_ref) on evidence.period_digest to evidence_sys;
create function evidence.verification_entities() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    return coalesce((
        select jsonb_agg(jsonb_build_object('tenant_id', e.tenant_id, 'entity_ref', e.entity_ref)
                         order by e.tenant_id, e.entity_ref)
        from (select a.tenant_id, a.entity_ref from evidence.applied_record a
              union
              select d.tenant_id, d.entity_ref from evidence.period_digest d) e
    ), '[]'::jsonb);
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.verification_entities() owner to evidence_sys;
revoke all on function evidence.verification_entities() from public;
grant execute on function evidence.verification_entities() to evidence_runtime;
revoke create on schema evidence from evidence_sys;
