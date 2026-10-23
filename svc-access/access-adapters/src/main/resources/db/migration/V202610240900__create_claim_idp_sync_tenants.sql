-- V202610240900__create_claim_idp_sync_tenants.sql (INV-T-4938, INV-Q-8843 A; CS-19, ARC 4.3 step 1a)
-- The claim function of the directory-group synchronisation scheduler: the distinct tenants that hold an ACTIVE
-- mapping and nothing else. Owned by the NOLOGIN access_sys, static SQL, executable only by access_runtime. No table
-- is added; access_sys sees the two columns it needs and only the rows of an ACTIVE mapping (no USING (true) policy,
-- no BYPASSRLS on any login).
SET lock_timeout = '5s';

GRANT SELECT (tenant_id, status) ON access.idp_group_mapping TO access_sys;
CREATE POLICY idp_group_mapping_sys_claim ON access.idp_group_mapping FOR SELECT TO access_sys
    USING (status = 'ACTIVE');

CREATE FUNCTION access.claim_idp_sync_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT DISTINCT m.tenant_id FROM access.idp_group_mapping m WHERE m.status = 'ACTIVE'
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.claim_idp_sync_tenants() OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.claim_idp_sync_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.claim_idp_sync_tenants() TO access_runtime;
