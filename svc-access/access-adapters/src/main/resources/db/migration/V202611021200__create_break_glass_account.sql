SET lock_timeout = '5s';

-- V202611021200__create_break_glass_account.sql (INV-T-8853; INV-REQ-896, INV-REQ-897; INV-Q-8837 A, INV-Q-8893 A;
-- CS-19, ARC-20). Run only by the service's migration Job as access_migrate (never at start-up). The sealed local
-- break-glass account of a tenant or installation: one row per tenant, written in the transaction that makes the
-- first tenant.admin grant ACTIVE. Only the SHA-256 digest of the sealing credential is stored (lower-case hex, 64
-- characters); the credential itself is returned once by the call that created or rotated it and is kept nowhere.
-- The runtime login never deletes.

CREATE TABLE access.break_glass_account (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    verifier      text        NOT NULL CHECK (verifier ~ '^[0-9a-f]{64}$'),
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id)
);

ALTER TABLE access.break_glass_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.break_glass_account FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.break_glass_account
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.break_glass_account TO access_runtime;
