-- V202611081500: the rendition attestation of an entity, one row per document class (INV-REQ-1031, INV-REQ-1034), and
-- the last RenditionFindingChanged sequence applied per entity (INV-Q-16391). Both tables are tenant-scoped with forced
-- RLS on the signed binding (INV-REQ-854 b1). The attester is the caller's user ID (INV-Q-16397). Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.rendition_attestation (
    tenant_id       uuid        NOT NULL,
    entity_id       uuid        NOT NULL,
    document_class  text        NOT NULL CHECK (length(document_class) BETWEEN 1 AND 64 AND document_class = btrim(document_class)),
    renderer        text        NOT NULL CHECK (renderer IN ('SELF', 'AGENT', 'AGGREGATOR')),
    attested_by     text        NOT NULL CHECK (length(attested_by) BETWEEN 1 AND 128),
    attested_at     timestamptz NOT NULL,
    state           text        NOT NULL CHECK (state IN ('CURRENT', 'REQUESTED_AGAIN')),
    PRIMARY KEY (tenant_id, entity_id, document_class),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

CREATE TABLE tenancy.rendition_finding_sequence (
    tenant_id      uuid   NOT NULL,
    entity_id      uuid   NOT NULL,
    last_sequence  bigint NOT NULL CHECK (last_sequence >= 1),
    PRIMARY KEY (tenant_id, entity_id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

ALTER TABLE tenancy.rendition_attestation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.rendition_attestation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.rendition_attestation
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.rendition_finding_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.rendition_finding_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.rendition_finding_sequence
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.rendition_attestation TO tenancy_runtime;
GRANT UPDATE (renderer, attested_by, attested_at, state) ON tenancy.rendition_attestation TO tenancy_runtime;
GRANT SELECT, INSERT ON tenancy.rendition_finding_sequence TO tenancy_runtime;
GRANT UPDATE (last_sequence) ON tenancy.rendition_finding_sequence TO tenancy_runtime;
