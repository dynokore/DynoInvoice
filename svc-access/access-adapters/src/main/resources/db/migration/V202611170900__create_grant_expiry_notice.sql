-- V202611170900__create_grant_expiry_notice.sql (INV-T-15799; INV-REQ-893, INV-REQ-1267; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: the expiry notices already handled and the ACC-003 alert already raised are recorded, never removed.

-- One row per (grant, notice day): the day is the number of days before expiry. A notice is either sent (NOTICED) or
-- recorded as superseded by a later one (SUPERSEDED, INV-Q-13245 A).
CREATE TABLE access.grant_expiry_notice (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    grant_ref     text        NOT NULL CHECK (length(grant_ref) BETWEEN 1 AND 64),
    days_before   integer     NOT NULL CHECK (days_before >= 1),
    outcome       text        NOT NULL CHECK (outcome IN ('NOTICED', 'SUPERSEDED')),
    recorded_at   timestamptz NOT NULL,
    UNIQUE (tenant_id, grant_ref, days_before),
    FOREIGN KEY (tenant_id, grant_ref) REFERENCES access.role_grant (tenant_id, grant_ref)
);

-- One row per grant: ACC-003 was raised for the expiry of this external grant.
CREATE TABLE access.grant_expiry_alert (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    grant_ref     text        NOT NULL CHECK (length(grant_ref) BETWEEN 1 AND 64),
    raised_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, grant_ref),
    FOREIGN KEY (tenant_id, grant_ref) REFERENCES access.role_grant (tenant_id, grant_ref)
);

ALTER TABLE access.grant_expiry_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.grant_expiry_notice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.grant_expiry_notice
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.grant_expiry_alert ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.grant_expiry_alert FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.grant_expiry_alert
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT ON access.grant_expiry_notice TO access_runtime;
GRANT SELECT, INSERT ON access.grant_expiry_alert TO access_runtime;

-- The claim function of the expiry scan (ARC 4.3 step 1a): the distinct tenants that hold an ACTIVE grant with an end
-- date, and nothing else. Owned by the NOLOGIN access_sys, static SQL, executable only by access_runtime; access_sys
-- sees the three columns it needs and only the rows of an ACTIVE time-limited grant (no USING (true) policy, no
-- BYPASSRLS on any login).
SET lock_timeout = '5s';

GRANT SELECT (tenant_id, status, valid_until) ON access.role_grant TO access_sys;
CREATE POLICY role_grant_expiry_claim ON access.role_grant FOR SELECT TO access_sys
    USING (status = 'ACTIVE' AND valid_until IS NOT NULL);

CREATE FUNCTION access.claim_grant_expiry_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT DISTINCT g.tenant_id FROM access.role_grant g WHERE g.status = 'ACTIVE' AND g.valid_until IS NOT NULL
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.claim_grant_expiry_tenants() OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.claim_grant_expiry_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.claim_grant_expiry_tenants() TO access_runtime;
