-- V202611201100: the replica of SubmissionDespatched facts (INV-REQ-1243, INV-AC-16774, INV-Q-16675 option A). One row
-- per (tenant, entity ref) holding the time of the first event seen and its Doc UUID; no row kind and no marker row
-- (SubmissionDespatched is a domain event of one registered entity, INV-REQ-1162). Insert-only, tenant-scoped with
-- forced RLS on the signed binding (INV-REQ-854 b1). Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.despatch_fact (
    tenant_id           uuid        NOT NULL,
    entity_ref          text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    first_despatched_at timestamptz NOT NULL,
    doc_uuid            uuid,
    PRIMARY KEY (tenant_id, entity_ref)
);

ALTER TABLE tenancy.despatch_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.despatch_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.despatch_fact
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.despatch_fact TO tenancy_runtime;
