-- V202610230910__create_principal_credential.sql (INV-T-4937; INV-REQ-880, INV-REQ-584)
-- One credential of a service principal. Only the verifier of a secret is kept (a SHA-256 of a 256-bit random secret),
-- never the secret; a public key or a certificate thumbprint is public material. A credential is withdrawn by status
-- and ends by valid_until; the runtime login never deletes.

CREATE TABLE access.principal_credential (
    id                      uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL,
    principal_ref           text        NOT NULL CHECK (length(principal_ref) BETWEEN 1 AND 64),
    credential_ref          text        NOT NULL CHECK (length(credential_ref) BETWEEN 1 AND 64),
    method                  text        NOT NULL CHECK (method IN ('PRIVATE_KEY_JWT', 'MTLS', 'CLIENT_SECRET_BASIC')),
    status                  text        NOT NULL CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    valid_until             timestamptz,
    public_key_jwk          text        CHECK (length(public_key_jwk) <= 8192),
    certificate_thumbprint  text        CHECK (length(certificate_thumbprint) BETWEEN 1 AND 128),
    secret_verifier         text        CHECK (length(secret_verifier) = 64),
    created_at              timestamptz NOT NULL,
    created_by              text        NOT NULL,
    updated_at              timestamptz NOT NULL,
    updated_by              text        NOT NULL,
    version                 bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, credential_ref),
    -- exactly the material of the method
    CHECK ((method = 'PRIVATE_KEY_JWT') = (public_key_jwk IS NOT NULL)
        AND (method = 'MTLS') = (certificate_thumbprint IS NOT NULL)
        AND (method = 'CLIENT_SECRET_BASIC') = (secret_verifier IS NOT NULL))
);
CREATE INDEX principal_credential_principal ON access.principal_credential (tenant_id, principal_ref);

ALTER TABLE access.principal_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.principal_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.principal_credential
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.principal_credential TO access_runtime;
