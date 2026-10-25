-- V2__evidence_chain.sql (INV-T-7218; INV-Q-7213 A).
--
-- The four chain tables hold exactly the columns of JdbcEvidenceLedger.COLUMNS (evidence-store), followed by
-- EvidenceLedgerSchema.trailerDdl("evidence", <table>, "evidence_runtime") (the signed-binding policy of
-- INV-T-4156, REVOKE UPDATE/DELETE/TRUNCATE and the append-only trigger). The three stores after them are
-- append-only the same way. ChainTablesTest compares the catalogue with COLUMNS, so a copy that drifts fails.

create table evidence.decision_trace (id uuid primary key, tenant_id uuid not null, entity_ref text not null, period_ref text not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, unique (tenant_id, entity_ref, period_ref, seq));
comment on column evidence.decision_trace.content is 'class: content';
ALTER TABLE evidence.decision_trace ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.decision_trace FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.decision_trace
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.decision_trace FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.decision_trace_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER decision_trace_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.decision_trace
    FOR EACH ROW EXECUTE FUNCTION evidence.decision_trace_immutable();
grant select, insert on evidence.decision_trace to evidence_runtime;

create table evidence.evidence_payload (id uuid primary key, tenant_id uuid not null, entity_ref text not null, period_ref text not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, unique (tenant_id, entity_ref, period_ref, seq));
comment on column evidence.evidence_payload.content is 'class: content';
ALTER TABLE evidence.evidence_payload ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.evidence_payload FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.evidence_payload
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.evidence_payload FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.evidence_payload_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER evidence_payload_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.evidence_payload
    FOR EACH ROW EXECUTE FUNCTION evidence.evidence_payload_immutable();
grant select, insert on evidence.evidence_payload to evidence_runtime;

create table evidence.acknowledgement (id uuid primary key, tenant_id uuid not null, entity_ref text not null, period_ref text not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, unique (tenant_id, entity_ref, period_ref, seq));
comment on column evidence.acknowledgement.content is 'class: content';
ALTER TABLE evidence.acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.acknowledgement
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.acknowledgement FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.acknowledgement_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER acknowledgement_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.acknowledgement
    FOR EACH ROW EXECUTE FUNCTION evidence.acknowledgement_immutable();
grant select, insert on evidence.acknowledgement to evidence_runtime;

create table evidence.audit_log (id uuid primary key, tenant_id uuid not null, entity_ref text not null, period_ref text not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, unique (tenant_id, entity_ref, period_ref, seq));
comment on column evidence.audit_log.content is 'class: content';
ALTER TABLE evidence.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.audit_log
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.audit_log FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER audit_log_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.audit_log
    FOR EACH ROW EXECUTE FUNCTION evidence.audit_log_immutable();
grant select, insert on evidence.audit_log to evidence_runtime;

-- One row per (tenant, doc_uuid, version): the entity and period of a document version (INV-Q-7213 A (1)).
create table evidence.document_locator (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null,
    entity_ref text not null,
    period_ref text not null,
    transaction_date date not null,
    trace_reference text not null,
    document_number text,
    source_event_id text not null,
    recorded_at timestamptz not null,
    unique (tenant_id, doc_uuid, version),
    unique (tenant_id, source_event_id)
);

ALTER TABLE evidence.document_locator ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.document_locator FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.document_locator
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.document_locator FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.document_locator_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER document_locator_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.document_locator
    FOR EACH ROW EXECUTE FUNCTION evidence.document_locator_immutable();
grant select, insert on evidence.document_locator to evidence_runtime;

-- A document-bound record that arrived before its locator (INV-Q-7213 A (3)); read by drainPending only.
create table evidence.pending_record (
    tenant_id uuid not null,
    source_event_id text not null,
    kind text not null,
    doc_uuid uuid not null,
    version integer not null,
    event_json text not null,
    arrived_at timestamptz not null,
    unique (tenant_id, source_event_id)
);
comment on column evidence.pending_record.event_json is 'class: content';
create index pending_record_document on evidence.pending_record (tenant_id, doc_uuid, version, arrived_at);

ALTER TABLE evidence.pending_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.pending_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.pending_record
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.pending_record FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.pending_record_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER pending_record_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.pending_record
    FOR EACH ROW EXECUTE FUNCTION evidence.pending_record_immutable();
grant select, insert on evidence.pending_record to evidence_runtime;

-- The only marker of an applied event: the chain tables carry no event id (INV-Q-7213 A (3)).
create table evidence.applied_record (
    tenant_id uuid not null,
    source_event_id text not null,
    kind text not null,
    entity_ref text not null,
    period_ref text not null,
    seq bigint not null,
    applied_at timestamptz not null,
    unique (tenant_id, source_event_id)
);

ALTER TABLE evidence.applied_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.applied_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.applied_record
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.applied_record FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.applied_record_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER applied_record_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.applied_record
    FOR EACH ROW EXECUTE FUNCTION evidence.applied_record_immutable();
grant select, insert on evidence.applied_record to evidence_runtime;
