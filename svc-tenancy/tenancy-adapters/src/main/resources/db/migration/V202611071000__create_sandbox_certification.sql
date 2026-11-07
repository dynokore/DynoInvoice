-- V202611071000: the sandbox certification records of an entity (INV-Q-16389, INV-Q-16403) and the stale state of its
-- certification (INV-REQ-1135, INV-Q-16393). Both tables are tenant-scoped with forced RLS on the signed binding
-- (INV-REQ-854 b1). A record is insert-only (a repeat of (entity, suite version) adds one more); the status row of an
-- entity with a PASSED record moves between CURRENT and STALE. Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.sandbox_certification (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    entity_id         uuid        NOT NULL,
    outcome           text        NOT NULL CHECK (outcome IN ('PASSED', 'FAILED')),
    suite_version     text        NOT NULL CHECK (length(suite_version) BETWEEN 1 AND 32),
    failed_scenarios  text[],
    recorded_by       text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 200),
    recorded_at       timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id),
    -- failedScenarios: required non-empty when FAILED, absent when PASSED (INV-Q-16403)
    CHECK ((outcome = 'FAILED') = (failed_scenarios IS NOT NULL AND cardinality(failed_scenarios) >= 1))
);

CREATE INDEX sandbox_certification_latest
    ON tenancy.sandbox_certification (tenant_id, entity_id, recorded_at DESC, id);

CREATE TABLE tenancy.certification_status (
    tenant_id      uuid        NOT NULL,
    entity_id      uuid        NOT NULL,
    status         text        NOT NULL CHECK (status IN ('CURRENT', 'STALE')),
    stale_trigger  text        CHECK (stale_trigger IN ('NEW_TRAIN', 'NEW_PACK', 'ISSUANCE_MODE', 'IDENTITY_TRIPLE', 'SCHEME_HANDLER')),
    changed_at     timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, entity_id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id),
    CHECK ((status = 'STALE') = (stale_trigger IS NOT NULL))
);

ALTER TABLE tenancy.sandbox_certification ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.sandbox_certification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.sandbox_certification
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.certification_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.certification_status FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.certification_status
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.sandbox_certification TO tenancy_runtime;
GRANT SELECT, INSERT ON tenancy.certification_status TO tenancy_runtime;
GRANT UPDATE (status, stale_trigger, changed_at) ON tenancy.certification_status TO tenancy_runtime;
