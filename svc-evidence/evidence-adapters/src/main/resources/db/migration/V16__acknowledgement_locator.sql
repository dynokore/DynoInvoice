-- V16__acknowledgement_locator.sql (INV-T-20571; INV-REQ-691, ruling of 2026-10-14 case 1).
--
-- One row per Acknowledgement ID of a SubmissionAcknowledged event: the (Doc UUID, version) the acknowledgement
-- belongs to. The lookup by Acknowledgement ID reads the chain of that version through applied_record. The table is
-- append-only the same way as evidence.evidence_artefact in V13 (CS-22). It alters no existing object.

create table evidence.acknowledgement_locator (
    tenant_id uuid not null,
    ack_id text not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    source_event_id text not null,
    recorded_at timestamptz not null,
    unique (tenant_id, ack_id),
    unique (tenant_id, source_event_id)
);
ALTER TABLE evidence.acknowledgement_locator ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.acknowledgement_locator FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.acknowledgement_locator
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.acknowledgement_locator FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.acknowledgement_locator_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER acknowledgement_locator_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.acknowledgement_locator
    FOR EACH ROW EXECUTE FUNCTION evidence.acknowledgement_locator_immutable();
grant select, insert on evidence.acknowledgement_locator to evidence_runtime;
