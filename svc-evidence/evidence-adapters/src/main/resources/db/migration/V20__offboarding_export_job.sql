-- V20__offboarding_export_job.sql (INV-T-22954; INV-REQ-877, CS-19)
-- The whole-tenant export job: one row per tenant and offboarding reference. Only a REQUESTED row may change;
-- READY and FAILED are terminal. The runtime login holds a column-level UPDATE and no DELETE.

create table evidence.offboarding_export_job (
    tenant_id uuid not null,
    job_id uuid not null,
    offboarding_ref text not null,
    export_ref text not null,
    scope text not null check (scope in ('TENANT', 'ENTITY')),
    entity_ref text,
    requested_event_id text not null,
    state text not null check (state in ('REQUESTED', 'READY', 'FAILED')),
    requested_at timestamptz not null,
    finished_at timestamptz,
    manifest_uri text,
    item_count bigint,
    contents text[],
    failure_reason text check (char_length(failure_reason) <= 500),
    primary key (tenant_id, job_id),
    unique (tenant_id, offboarding_ref)
);
ALTER TABLE evidence.offboarding_export_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.offboarding_export_job FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.offboarding_export_job
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
grant select, insert, update (state, finished_at, manifest_uri, item_count, contents, failure_reason)
    on evidence.offboarding_export_job to evidence_runtime;

CREATE OR REPLACE FUNCTION evidence.offboarding_export_job_terminal() RETURNS trigger AS $$
BEGIN
    IF OLD.state IN ('READY', 'FAILED') THEN
        RAISE EXCEPTION 'CS-22: % is terminal in state %; % is refused', TG_TABLE_NAME, OLD.state, TG_OP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER offboarding_export_job_terminal
    BEFORE UPDATE ON evidence.offboarding_export_job
    FOR EACH ROW EXECUTE FUNCTION evidence.offboarding_export_job_terminal();
