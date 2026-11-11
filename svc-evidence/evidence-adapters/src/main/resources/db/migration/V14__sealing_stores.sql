-- V14__sealing_stores.sql (INV-T-20567; INV-REQ-645 behavior 1-2; ports of INV-T-13389).
--
-- The tables behind HeldPayloadStore, ParkedDespatchStore, PayloadQuarantineStore and SealedPayloadSink. Every table is
-- tenant-isolated (row-level security enabled and forced, policy tenant_isolation as V13). held_payload is the one
-- mutable table: HeldPayloadStore.remove replaces a corrupt row, so evidence_runtime also holds DELETE on it. The
-- other three are append-only (CS-22, as V13). Nothing existing is altered.

create table evidence.held_payload (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    event_id text not null,
    payload_bytes bytea not null,
    payload_sha256 text not null,
    recorded_at timestamptz not null,
    unique (tenant_id, doc_uuid, version)
);
comment on column evidence.held_payload.payload_bytes is 'class: content';
ALTER TABLE evidence.held_payload ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.held_payload FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.held_payload
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, TRUNCATE ON evidence.held_payload FROM evidence_runtime;
grant select, insert, delete on evidence.held_payload to evidence_runtime;

-- arrival_seq keeps the order of arrival for parkedFor; recorded_at alone ties inside one transaction.
create table evidence.parked_despatch (
    arrival_seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    event_id text not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    attempt_number integer not null check (attempt_number >= 1),
    payload_sha256 text not null,
    despatched_at timestamptz not null,
    recorded_at timestamptz not null,
    unique (tenant_id, event_id)
);
create index parked_despatch_version_idx on evidence.parked_despatch (tenant_id, doc_uuid, version, arrival_seq);
ALTER TABLE evidence.parked_despatch ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.parked_despatch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.parked_despatch
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));

create table evidence.payload_quarantine (
    tenant_id uuid not null,
    caused_by_event_id text not null,
    origin text not null check (origin in ('RECEIVED', 'STORED')),
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    payload_bytes bytea not null,
    payload_sha256 text not null,
    recorded_at timestamptz not null,
    unique (tenant_id, caused_by_event_id, origin)
);
comment on column evidence.payload_quarantine.payload_bytes is 'class: content';
ALTER TABLE evidence.payload_quarantine ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.payload_quarantine FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.payload_quarantine
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));

-- Links each version to its sealed EVIDENCE_PAYLOAD record; written with the append in one transaction.
create table evidence.sealed_payload (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    source_event_id text not null,
    recorded_at timestamptz not null,
    unique (tenant_id, source_event_id)
);
create index sealed_payload_version_idx on evidence.sealed_payload (tenant_id, doc_uuid, version);
ALTER TABLE evidence.sealed_payload ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.sealed_payload FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.sealed_payload
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));

REVOKE UPDATE, DELETE, TRUNCATE ON evidence.parked_despatch, evidence.payload_quarantine, evidence.sealed_payload
    FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.sealing_store_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER parked_despatch_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.parked_despatch
    FOR EACH ROW EXECUTE FUNCTION evidence.sealing_store_immutable();
CREATE TRIGGER payload_quarantine_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.payload_quarantine
    FOR EACH ROW EXECUTE FUNCTION evidence.sealing_store_immutable();
CREATE TRIGGER sealed_payload_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.sealed_payload
    FOR EACH ROW EXECUTE FUNCTION evidence.sealing_store_immutable();
grant select, insert on evidence.parked_despatch, evidence.payload_quarantine, evidence.sealed_payload
    to evidence_runtime;
