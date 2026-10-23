-- V202610250900__resolve_principal_tenant.sql (INV-T-4937, INV-Q-8982 A; CS-19, ARC 4.3 step 1a)
-- The claim port of the gateway call authenticatePrincipal: the tenant of the one service principal with a given
-- reference, and nothing else. Owned by the NOLOGIN access_sys, static SQL, executable only by access_runtime. The one
-- policy applies to access_sys only, and access_sys sees the two columns it needs (no BYPASSRLS on any login).
SET lock_timeout = '5s';

CREATE UNIQUE INDEX service_principal_principal_ref_key ON access.service_principal (principal_ref);

GRANT SELECT (principal_ref, tenant_id) ON access.service_principal TO access_sys;
CREATE POLICY service_principal_sys_resolve ON access.service_principal FOR SELECT TO access_sys
    USING (true);

CREATE FUNCTION access.resolve_principal_tenant(principal_ref text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT p.tenant_id FROM access.service_principal p WHERE p.principal_ref = resolve_principal_tenant.principal_ref
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.resolve_principal_tenant(text) OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.resolve_principal_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.resolve_principal_tenant(text) TO access_runtime;
