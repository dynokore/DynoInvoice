SET lock_timeout = '5s';

-- V202611011000__accept_first_administrator_invitation.sql (INV-T-8227; INV-Q-8185 A, INV-Q-14264 A; CS-19, ARC 4.3)
-- Run only by the service's migration Job as access_migrate (never at start-up). The acceptance of a first
-- tenant.admin invitation arrives with a token that carries no tenant, so the tenant is found from the invitation
-- reference alone, as resolve_principal_tenant finds the tenant of a service principal: a SECURITY DEFINER function
-- owned by the NOLOGIN access_sys, static SQL, executable only by access_runtime. The one policy applies to
-- access_sys only and access_sys sees the two columns it needs (no BYPASSRLS on any login). The reference must name
-- one invitation, so it is unique on its own; the tenant-scoped unique key stays.

CREATE UNIQUE INDEX first_administrator_invitation_ref_key ON access.first_administrator_invitation (invitation_ref);

GRANT SELECT (invitation_ref, tenant_id) ON access.first_administrator_invitation TO access_sys;
CREATE POLICY invitation_sys_resolve ON access.first_administrator_invitation FOR SELECT TO access_sys
    USING (true);

CREATE FUNCTION access.resolve_invitation_tenant(invitation_ref text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT i.tenant_id FROM access.first_administrator_invitation i
    WHERE i.invitation_ref = resolve_invitation_tenant.invitation_ref
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.resolve_invitation_tenant(text) OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.resolve_invitation_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.resolve_invitation_tenant(text) TO access_runtime;
