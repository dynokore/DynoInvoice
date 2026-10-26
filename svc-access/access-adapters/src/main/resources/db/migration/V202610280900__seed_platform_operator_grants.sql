-- V202610280900__seed_platform_operator_grants.sql (INV-T-9280; INV-Q-9073 C, INV-Q-11223 A, INV-Q-11225 C; INV-REQ-862)
-- Run only by the service's migration Job as access_migrate (never at start-up). Operator-role grants under the platform
-- tenant exist only through this change-controlled migration: no access operation writes them. A later change of the
-- staff list is a new versioned migration; db/seed/platform-operator-grants.tsv holds the same list and a test compares them.
SET lock_timeout = '5s';

-- access_migrate owns the tables and the forced row-level security applies to it. tenant_isolation moves to the one role
-- that holds table privileges (it applies to PUBLIC otherwise, and access_migrate may not execute access.bound_tenant()),
-- and access_migrate gets one INSERT policy limited to the platform tenant.
ALTER POLICY tenant_isolation ON access.identity TO access_runtime;
ALTER POLICY tenant_isolation ON access.role_grant TO access_runtime;
CREATE POLICY platform_seed_insert ON access.identity FOR INSERT TO access_migrate
    WITH CHECK (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY platform_seed_insert ON access.role_grant FOR INSERT TO access_migrate
    WITH CHECK (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);

CREATE TEMPORARY TABLE platform_seed (tenant_id uuid NOT NULL DEFAULT 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid,
    identity_ref text NOT NULL, role text NOT NULL) ON COMMIT DROP;
INSERT INTO platform_seed (identity_ref, role)
SELECT seed.identity_ref, seed.role FROM (VALUES
-- seed rows begin
-- seed rows end
    (NULL::text, NULL::text)) AS seed (identity_ref, role)
WHERE seed.identity_ref IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM platform_seed WHERE role NOT IN
        ('platform.support', 'platform.engineer', 'release.manager', 'commercial.admin', 'regulatory.author', 'regulatory.approver')) THEN
        RAISE EXCEPTION 'a platform seed row names a role that is not an operator-role wire name';
    END IF;
END
$$;

INSERT INTO access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, updated_at, updated_by, version)
SELECT gen_random_uuid(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, seed.identity_ref, 'STAFF', now(),
    'V202610280900__seed_platform_operator_grants', now(), 'V202610280900__seed_platform_operator_grants', 0
FROM (SELECT DISTINCT identity_ref FROM platform_seed) AS seed
ON CONFLICT DO NOTHING;

INSERT INTO access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, scope_ref, valid_from, status,
    grant_source, created_at, created_by, updated_at, updated_by, version)
SELECT gen_random_uuid(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'seed-' || md5(seed.identity_ref || E'\t' || seed.role),
    seed.identity_ref, seed.role, 'TENANT', 'ffffffff-ffff-ffff-ffff-ffffffffffff', now(), 'ACTIVE', 'BY_HAND', now(),
    'V202610280900__seed_platform_operator_grants', now(), 'V202610280900__seed_platform_operator_grants', 0
FROM platform_seed AS seed
ON CONFLICT DO NOTHING;
