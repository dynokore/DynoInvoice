-- V202611091300: the recorded acknowledgement that overrides the unsubmitted-document block of deregisterEntity
-- (INV-REQ-956 b1; INV-T-4931). One row per override, inserted in the transaction of the deregistration and never
-- updated. Run by tenancy_migrate. Forced RLS on the signed binding (INV-REQ-854 b1). The acknowledging person is a
-- user ID, never a role (INV-Q-10647).
SET lock_timeout = '5s';

CREATE TABLE tenancy.unsubmitted_override (
    tenant_id        uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    entity_ref       text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    acknowledged_by  text        NOT NULL CHECK (length(acknowledged_by) BETWEEN 1 AND 128),
    statement        text        NOT NULL CHECK (length(statement) BETWEEN 1 AND 1000),
    recorded_on      date        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX unsubmitted_override_entity ON tenancy.unsubmitted_override (tenant_id, entity_ref);

ALTER TABLE tenancy.unsubmitted_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.unsubmitted_override FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.unsubmitted_override
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.unsubmitted_override TO tenancy_runtime;
