-- V13__evidence_artefact.sql (INV-T-20310; INV-REQ-1036 behavior 2).
--
-- One row per distinct (tenant, artefact type, reference, hash) of an EvidenceArtefactRecorded event. A repeat, or a
-- second producer of the same artefact, stores nothing more. An artefact type outside the known set is stored as
-- received. The table is append-only the same way as evidence.decision_trace in V2 (CS-22).

create table evidence.evidence_artefact (
    id uuid primary key,
    tenant_id uuid not null,
    entity_ref text,
    artefact_type text not null,
    reference text not null,
    hash char(64) not null check (hash ~ '^[0-9a-f]{64}$'),
    event_id text not null,
    recorded_at timestamptz not null,
    unique (tenant_id, artefact_type, reference, hash)
);
ALTER TABLE evidence.evidence_artefact ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.evidence_artefact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.evidence_artefact
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.evidence_artefact FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.evidence_artefact_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER evidence_artefact_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.evidence_artefact
    FOR EACH ROW EXECUTE FUNCTION evidence.evidence_artefact_immutable();
grant select, insert on evidence.evidence_artefact to evidence_runtime;
