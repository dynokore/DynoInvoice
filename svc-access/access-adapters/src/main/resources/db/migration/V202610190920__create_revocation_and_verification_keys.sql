-- V202610190920__create_revocation_and_verification_keys.sql (INV-T-4934; INV-API-4001, INV-TS-4002)
-- The gateway's token material. It is platform data, not tenant data, so its rows carry the reserved platform tenant
-- (tenancy-kernel TenantId.PLATFORM) and are read under that binding; the column is still NOT NULL under forced RLS.

CREATE TABLE access.revocation_entry (
    id            uuid        PRIMARY KEY,                                   -- the entryId of the contract
    tenant_id     uuid        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 1),
    kind          text        NOT NULL CHECK (kind IN ('PRINCIPAL', 'SUBJECT', 'TOKEN')),
    principal_ref text        CHECK (length(principal_ref) BETWEEN 1 AND 64),
    subject       text        CHECK (length(subject) BETWEEN 1 AND 255),
    token_id      text        CHECK (length(token_id) BETWEEN 1 AND 128),
    revoked_at    timestamptz NOT NULL,
    UNIQUE (tenant_id, version),
    CHECK (num_nonnulls(principal_ref, subject, token_id) = 1),
    CHECK ((kind = 'PRINCIPAL') = (principal_ref IS NOT NULL) AND (kind = 'SUBJECT') = (subject IS NOT NULL)
        AND (kind = 'TOKEN') = (token_id IS NOT NULL))
);
CREATE INDEX revocation_entry_revoked_at ON access.revocation_entry (tenant_id, revoked_at);

-- The highest list version ever assigned: it does not fall when an entry expires (INV-API-4001)
CREATE TABLE access.revocation_head (
    tenant_id     uuid        PRIMARY KEY,
    version       bigint      NOT NULL CHECK (version >= 0)
);

CREATE TABLE access.verification_key (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    kid           text        NOT NULL CHECK (length(kid) BETWEEN 1 AND 32),
    alg           text        NOT NULL CHECK (length(alg) BETWEEN 1 AND 16),
    public_key    text        NOT NULL CHECK (length(public_key) BETWEEN 1 AND 8192),
    valid_from    timestamptz NOT NULL,
    valid_until   timestamptz NOT NULL CHECK (valid_until > valid_from),
    UNIQUE (tenant_id, kid)
);

ALTER TABLE access.revocation_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.revocation_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.revocation_entry
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.revocation_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.revocation_head FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.revocation_head
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.verification_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.verification_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.verification_key
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT ON access.revocation_entry TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.revocation_head TO access_runtime;
GRANT SELECT, INSERT ON access.verification_key TO access_runtime;
