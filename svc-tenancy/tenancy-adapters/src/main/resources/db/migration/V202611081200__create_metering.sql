-- V202611081200: metering counters, archive volume, plan limits and the once-per-period limit marker
-- (INV-REQ-878, INV-REQ-879; INV-AC-9587, INV-AC-16558). Run by tenancy_migrate. Forced RLS on the signed binding
-- (INV-REQ-854 b1). Every table is keyed by more than the tenant alone (INV-AC-2041).
SET lock_timeout = '5s';

-- Submissions counted per tenant, entity (the entity `ref`) and UTC calendar month (YYYY-MM).
CREATE TABLE tenancy.usage_counter (
    tenant_id     uuid    NOT NULL REFERENCES tenancy.tenant (tenant_id),
    entity_ref    text    NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    period        text    NOT NULL CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    documents     integer NOT NULL DEFAULT 0 CHECK (documents >= 0),
    resubmissions integer NOT NULL DEFAULT 0 CHECK (resubmissions >= 0),
    PRIMARY KEY (tenant_id, entity_ref, period)
);

-- The latest measured archive volume per tenant and entity. The tenant-wide value (TenantWideArchiveVolumeMeasured)
-- is the row whose entity_ref is the reserved tenant-wide entity value (EntityRef.TENANT_WIDE); it is no entity and
-- never listed as one. No zero-length entity_ref is admitted.
CREATE TABLE tenancy.archive_volume (
    tenant_id   uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    entity_ref  text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    bytes       bigint      NOT NULL CHECK (bytes >= 0),
    measured_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, entity_ref)
);

-- recordEntitlement: one row per limit kind of the tenant, a repeat replaces.
CREATE TABLE tenancy.entitlement_limit (
    tenant_id     uuid    NOT NULL REFERENCES tenancy.tenant (tenant_id),
    limit_kind    text    NOT NULL CHECK (limit_kind IN ('DOCUMENTS_PER_MONTH', 'ENTITIES')),
    plan_version  text    NOT NULL CHECK (length(plan_version) BETWEEN 1 AND 16),
    limit_value   integer NOT NULL CHECK (limit_value >= 0),
    PRIMARY KEY (tenant_id, limit_kind)
);

-- PlanLimitExceeded is published once per (tenant, limit kind, period); the period is '' for ENTITIES.
CREATE TABLE tenancy.plan_limit_raised (
    tenant_id  uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    limit_kind text        NOT NULL CHECK (limit_kind IN ('DOCUMENTS_PER_MONTH', 'ENTITIES')),
    period     text        NOT NULL CHECK (period = '' OR period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    raised_at  timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, limit_kind, period)
);

ALTER TABLE tenancy.usage_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.usage_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.usage_counter
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON tenancy.usage_counter TO tenancy_runtime;

ALTER TABLE tenancy.archive_volume ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.archive_volume FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.archive_volume
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON tenancy.archive_volume TO tenancy_runtime;

ALTER TABLE tenancy.entitlement_limit ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.entitlement_limit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.entitlement_limit
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON tenancy.entitlement_limit TO tenancy_runtime;

ALTER TABLE tenancy.plan_limit_raised ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.plan_limit_raised FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.plan_limit_raised
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON tenancy.plan_limit_raised TO tenancy_runtime;
