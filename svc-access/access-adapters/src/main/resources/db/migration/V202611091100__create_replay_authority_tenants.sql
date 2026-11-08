-- V202611091100__create_replay_authority_tenants.sql (INV-T-9061; INV-Q-9047 A; CS-19, ARC 4.3 step 1a)
-- The tenant list of the full sync of AuthorityChanged: the distinct tenants that hold a role grant, a consent or a
-- service principal, and nothing else. Owned by the NOLOGIN access_sys, static SQL, executable only by access_runtime.
-- No table is added; access_sys sees the one column it needs (tenant_id) of each table. consent and service_principal
-- already have a SELECT policy for access_sys; role_grant gets the one policy below (no BYPASSRLS on any login).
SET lock_timeout = '5s';

GRANT SELECT (tenant_id) ON access.role_grant TO access_sys;
GRANT SELECT (tenant_id) ON access.consent TO access_sys;
CREATE POLICY role_grant_sys_replay_tenants ON access.role_grant FOR SELECT TO access_sys USING (true);

CREATE FUNCTION access.replay_authority_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT t.tenant_id FROM (
        SELECT g.tenant_id FROM access.role_grant g
        UNION
        SELECT c.tenant_id FROM access.consent c
        UNION
        SELECT p.tenant_id FROM access.service_principal p
    ) t ORDER BY t.tenant_id
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.replay_authority_tenants() OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.replay_authority_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.replay_authority_tenants() TO access_runtime;
