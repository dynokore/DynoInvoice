SET lock_timeout = '5s';

-- V202611201000__document_version_net_derivation.sql (INV-T-25351; INV-REQ-558 behavior 6, owner ruling 2026-11-19).
-- document_version_net_derivation: the netDerivation of a system-derived version, one row per version, written in the
-- transaction that creates the version and never edited (the runtime login holds SELECT and INSERT only).
-- credit_notes is the JSON text of the credit note list [{"docUuid": ..., "version": ...}], in request order.
-- tenant_id, entity_id and partition_date are carried because the key of document_version spans its partition columns
-- (V3__document.sql), which a foreign key must name in full; tenant_id is also the row-security column (CS-19).
-- processed_net_derivation_request: the processed-request store of the idempotent consumer, keyed by the CloudEvents id.
create table canonical.document_version_net_derivation (
    doc_uuid uuid not null,
    version int not null check (version >= 1),
    tenant_id uuid not null,
    entity_id uuid not null,
    partition_date date not null,
    base_version int not null check (base_version >= 1 and base_version < version),
    credit_notes text not null check (length(credit_notes) > 0),
    primary key (doc_uuid, version),
    foreign key (doc_uuid, version, partition_date, entity_id)
        references canonical.document_version (doc_uuid, version, partition_date, entity_id)
);
create table canonical.processed_net_derivation_request (
    request_event_id uuid not null primary key,
    tenant_id uuid not null,
    processed_at timestamptz not null default now()
);
comment on column canonical.document_version_net_derivation.doc_uuid is 'class: operational';
comment on column canonical.document_version_net_derivation.version is 'class: operational';
comment on column canonical.document_version_net_derivation.tenant_id is 'class: operational';
comment on column canonical.document_version_net_derivation.entity_id is 'class: operational';
comment on column canonical.document_version_net_derivation.partition_date is 'class: operational';
comment on column canonical.document_version_net_derivation.base_version is 'class: operational';
comment on column canonical.document_version_net_derivation.credit_notes is 'class: operational';
comment on column canonical.processed_net_derivation_request.request_event_id is 'class: operational';
comment on column canonical.processed_net_derivation_request.tenant_id is 'class: operational';
comment on column canonical.processed_net_derivation_request.processed_at is 'class: operational';

ALTER TABLE canonical.document_version_net_derivation ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_net_derivation FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_net_derivation_tenant_isolation ON canonical.document_version_net_derivation
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.processed_net_derivation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.processed_net_derivation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY processed_net_derivation_request_tenant_isolation ON canonical.processed_net_derivation_request
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));

grant select, insert on canonical.document_version_net_derivation to canonical_runtime;
grant select, insert on canonical.processed_net_derivation_request to canonical_runtime;
