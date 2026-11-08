-- V202611081500: the migration cutover of an entity that moves from another compliance solution (INV-REQ-1109, owner
-- ruling INV-Q-6232 A): one row per entity holding the cutover date, the prior solution, the approving user id (never a
-- role), an optional evidence reference and the time it was recorded. The row is never updated or deleted: a different
-- date after the first is refused by the use case (409). Forced RLS on the signed binding as applied_profile_entity;
-- the runtime role may only read and insert. Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.migration_cutover (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    entity_id      uuid        NOT NULL,
    cutover_date   date        NOT NULL,
    prior_solution text        NOT NULL CHECK (length(prior_solution) BETWEEN 1 AND 128),
    approved_by    text        NOT NULL CHECK (length(approved_by) <= 128),
    evidence_ref   text        CHECK (length(evidence_ref) <= 64),
    recorded_at    timestamptz NOT NULL,
    UNIQUE (tenant_id, entity_id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

ALTER TABLE tenancy.migration_cutover ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.migration_cutover FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.migration_cutover
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.migration_cutover TO tenancy_runtime;
