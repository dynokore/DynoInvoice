-- INV-T-19568 (INV-REQ-837; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as masterdata_migrate (never at start-up). The expiry of the current
-- credential of a connection: one row per connection, replaced on create and on rotate, removed when a rotation
-- carries no expiry. It holds the secret-store reference only, never the secret.

CREATE TABLE masterdata.credential_expiry (
    id             uuid        PRIMARY KEY,                                   -- UUIDv7, assigned by the application
    tenant_id      uuid        NOT NULL,                                      -- FR-G.5
    connection_ref text        NOT NULL CHECK (length(connection_ref) BETWEEN 1 AND 40),
    credential_ref text        NOT NULL CHECK (length(credential_ref) BETWEEN 1 AND 100),
    expires_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, connection_ref)                                        -- one expiry row per connection
);

ALTER TABLE masterdata.credential_expiry ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.credential_expiry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.credential_expiry
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE, DELETE ON masterdata.credential_expiry TO masterdata_runtime;
