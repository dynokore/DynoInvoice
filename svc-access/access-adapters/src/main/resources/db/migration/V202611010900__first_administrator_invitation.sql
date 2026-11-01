SET lock_timeout = '5s';

-- V202611010900__first_administrator_invitation.sql (INV-T-8226; INV-Q-8185 A, INV-Q-14261 A; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The invitation of the first
-- tenant.admin of a tenant: one row per provisioning, tied to the PENDING grant it belongs to. The plaintext code is
-- never stored: it is derived (HMAC) from the invitation reference and the key version, and only its SHA-256 digest is
-- kept. The runtime login owns nothing and never deletes. No existing table changes.

CREATE TABLE access.first_administrator_invitation (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    invitation_ref      text        NOT NULL CHECK (length(invitation_ref) BETWEEN 1 AND 48),
    grant_ref           text        NOT NULL CHECK (length(grant_ref) BETWEEN 1 AND 64),
    invited_email       text        NOT NULL CHECK (length(invited_email) BETWEEN 3 AND 254),
    expires_at          timestamptz NOT NULL,
    code_digest         text        CHECK (code_digest IS NULL OR length(code_digest) BETWEEN 1 AND 128),  -- INV-T-8227 clears it
    code_key_version    text        NOT NULL CHECK (code_key_version ~ '^v[0-9]{1,3}$'),
    provisioning_key    uuid        NOT NULL,
    request_fingerprint text        NOT NULL CHECK (length(request_fingerprint) BETWEEN 1 AND 128),
    created_at          timestamptz NOT NULL,
    created_by          text        NOT NULL,
    updated_at          timestamptz NOT NULL,
    updated_by          text        NOT NULL,
    version             bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, invitation_ref),
    UNIQUE (tenant_id, provisioning_key),
    FOREIGN KEY (tenant_id, grant_ref) REFERENCES access.role_grant (tenant_id, grant_ref)
);

ALTER TABLE access.first_administrator_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.first_administrator_invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.first_administrator_invitation
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.first_administrator_invitation TO access_runtime;
