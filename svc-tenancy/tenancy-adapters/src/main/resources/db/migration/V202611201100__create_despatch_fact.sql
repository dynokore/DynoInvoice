-- V202611201100: the replica of SubmissionDespatched facts (INV-REQ-1243, INV-AC-16774, INV-Q-16675 option A). One row
-- per (tenant, entity ref) holding the time of the first event seen and its Doc UUID, and one unattributed marker per
-- tenant for an event that carries no entityref. Insert-only, tenant-scoped with forced RLS on the signed binding
-- (INV-REQ-854 b1). Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.despatch_fact (
    tenant_id           uuid        NOT NULL,
    row_kind            text        NOT NULL CHECK (row_kind IN ('ENTITY', 'UNATTRIBUTED')),
    entity_ref          text        CHECK (entity_ref IS NULL OR length(entity_ref) BETWEEN 1 AND 64),
    first_despatched_at timestamptz NOT NULL,
    doc_uuid            uuid,
    CHECK ((row_kind = 'ENTITY') = (entity_ref IS NOT NULL))
);

CREATE UNIQUE INDEX despatch_fact_entity ON tenancy.despatch_fact (tenant_id, entity_ref) WHERE row_kind = 'ENTITY';
CREATE UNIQUE INDEX despatch_fact_unattributed ON tenancy.despatch_fact (tenant_id, row_kind) WHERE row_kind = 'UNATTRIBUTED';

ALTER TABLE tenancy.despatch_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.despatch_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.despatch_fact
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.despatch_fact TO tenancy_runtime;
