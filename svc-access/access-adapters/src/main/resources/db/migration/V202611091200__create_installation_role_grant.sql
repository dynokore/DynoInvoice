-- V202611091200__create_installation_role_grant.sql (INV-T-9235; INV-Q-8972 A, INV-Q-9229 A, INV-Q-8789 A; CS-19)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- The INSTALLATION role grants published for the configured list `access.installation-roles`: one row per entry, keyed
-- by a deterministic reference. It is platform data, not tenant data (a grant at installation scope has no tenant), so
-- the table carries no tenant column and is declared platform-scoped; the row policy still limits every statement to a
-- transaction bound to the platform tenant (tenancy-kernel TenantId.PLATFORM), the binding of the start-up
-- reconciliation, so a tenant-bound transaction sees and writes nothing.
SET lock_timeout = '5s';

CREATE TABLE access.installation_role_grant (
    grant_ref         text        PRIMARY KEY CHECK (length(grant_ref) BETWEEN 1 AND 64),
    subject           text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
    role              text        NOT NULL CHECK (role IN ('platform.engineer', 'deployment.operator')),
    state             text        NOT NULL CHECK (state IN ('ACTIVE', 'REMOVED')),
    authority_version bigint      NOT NULL CHECK (authority_version >= 1),
    updated_at        timestamptz NOT NULL
);
COMMENT ON TABLE access.installation_role_grant IS 'platform-scoped: no tenant data';
COMMENT ON COLUMN access.installation_role_grant.subject IS 'class: personal';

ALTER TABLE access.installation_role_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.installation_role_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_binding_only ON access.installation_role_grant
    USING      ((select access.bound_tenant()) = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
    WITH CHECK ((select access.bound_tenant()) = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);

GRANT SELECT, INSERT, UPDATE ON access.installation_role_grant TO access_runtime;
