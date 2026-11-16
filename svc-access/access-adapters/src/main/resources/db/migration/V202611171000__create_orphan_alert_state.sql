-- V202611161100__create_orphan_alert_state.sql (INV-T-23857; INV-REQ-895 ACC-001; CS-19, ARC 4.3 step 1a)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- One row per entity whose ACC-001 is open: the row is the idempotence key, so the alert is raised once per open
-- condition. The row is deleted when an approver is active again.
SET lock_timeout = '5s';

CREATE TABLE access.orphan_alert_state (
    tenant_id   uuid        NOT NULL,
    entity_ref  text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 128),
    opened_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, entity_ref)
);

ALTER TABLE access.orphan_alert_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.orphan_alert_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.orphan_alert_state
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, DELETE ON access.orphan_alert_state TO access_runtime;

-- The claim function of the scheduled scan: the distinct tenants that hold any row in access.role_grant and nothing
-- else (a tenant with no grant has nobody to alert). Owned by the NOLOGIN access_sys, which already sees the
-- tenant_id column of role_grant through V202611091100 (column grant and SELECT policy); no new privilege is added.
CREATE FUNCTION access.claim_orphan_scan_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT DISTINCT g.tenant_id FROM access.role_grant g
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.claim_orphan_scan_tenants() OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.claim_orphan_scan_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.claim_orphan_scan_tenants() TO access_runtime;
