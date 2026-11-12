-- V18__submission_index.sql (INV-T-20719; INV-REQ-724, slice E4a of the reverse lookup).
--
-- Read model: ONE row per (tenant, document, version). The document write (this slice) fills the document columns
-- only where they are still null; the acknowledgement and despatch writes (later tasks) fill ack_id,
-- acknowledged_at and state. Rows with no document_date are partial projections and are never returned by a reader.
-- There is no category_code column: category codes live in the child table of a later task. A read model, not
-- evidence: no immutability trigger, UPDATE is granted, DELETE and TRUNCATE are not.

create table evidence.submission_index (
    tenant_id uuid not null,
    entity_ref text,
    doc_uuid uuid not null,
    version integer not null,
    period_ref text,
    document_number text,
    document_date date,
    counterparty_uen text,
    ack_id text,
    acknowledged_at timestamptz,
    state text,
    recorded_at timestamptz not null,
    primary key (tenant_id, doc_uuid, version)
);
comment on column evidence.submission_index.document_number is 'class: content';
comment on column evidence.submission_index.counterparty_uen is 'class: content';
create index submission_index_document_number on evidence.submission_index (tenant_id, document_number);
create index submission_index_counterparty_uen on evidence.submission_index (tenant_id, counterparty_uen);
create index submission_index_document_date on evidence.submission_index (tenant_id, document_date);
ALTER TABLE evidence.submission_index ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.submission_index FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.submission_index
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE ALL ON evidence.submission_index FROM evidence_runtime;
grant select, insert, update on evidence.submission_index to evidence_runtime;
