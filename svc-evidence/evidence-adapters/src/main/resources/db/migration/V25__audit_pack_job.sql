-- V25__audit_pack_job.sql (INV-T-24321; INV-REQ-988, CS-19, CS-22)
-- The audit-pack job: one row per tenant, entity and period (the contract repeatKey). Only a PENDING row may change;
-- READY and FAILED are terminal. The runtime login holds a column-level UPDATE and no DELETE. The acting user ID is
-- stored in requested_by, never a role (INV-Q-10647). The table holds job metadata, no evidence content.

create table evidence.audit_pack_job (
    tenant_id uuid not null,
    pack_id uuid not null,
    entity_ref text not null check (entity_ref <> ''),
    period_ref text not null check (period_ref <> ''),
    state text not null check (state in ('PENDING', 'READY', 'FAILED')),
    requested_at timestamptz not null,
    requested_by text not null check (requested_by <> ''),
    ready_at timestamptz,
    download_uri text,
    contents text[],
    failure_reason text check (char_length(failure_reason) <= 500),
    primary key (tenant_id, pack_id),
    unique (tenant_id, entity_ref, period_ref)
);
ALTER TABLE evidence.audit_pack_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.audit_pack_job FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.audit_pack_job
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
grant select, insert, update (state, ready_at, download_uri, contents, failure_reason)
    on evidence.audit_pack_job to evidence_runtime;

CREATE OR REPLACE FUNCTION evidence.audit_pack_job_terminal() RETURNS trigger AS $$
BEGIN
    IF OLD.state IN ('READY', 'FAILED') THEN
        RAISE EXCEPTION 'CS-22: % is terminal in state %; % is refused', TG_TABLE_NAME, OLD.state, TG_OP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER audit_pack_job_terminal
    BEFORE UPDATE ON evidence.audit_pack_job
    FOR EACH ROW EXECUTE FUNCTION evidence.audit_pack_job_terminal();
