-- V202610190910__create_identity_and_grant.sql (INV-T-4934; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). A grant is a triple of role, scope and
-- validity window (INV-REQ-864). The runtime login owns nothing and never deletes: a grant is revoked by status.

CREATE TABLE access.identity (
    id            uuid        PRIMARY KEY,                                   -- UUIDv7, assigned by the application
    tenant_id     uuid        NOT NULL,                                      -- FR-G.5
    identity_ref  text        NOT NULL CHECK (length(identity_ref) BETWEEN 1 AND 64),
    grantee_kind  text        NOT NULL CHECK (grantee_kind IN ('STAFF', 'EXTERNAL', 'CROSS_TENANT')),
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, identity_ref)
);

CREATE TABLE access.role_grant (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    grant_ref     text        NOT NULL CHECK (length(grant_ref) BETWEEN 1 AND 64),
    identity_ref  text        NOT NULL,
    role          text        NOT NULL CHECK (length(role) BETWEEN 1 AND 64),
    scope_level   text        NOT NULL CHECK (scope_level IN ('TENANT', 'GROUP', 'ENTITY', 'ENTITY_PERIOD', 'CONSENT')),
    scope_ref     text        NOT NULL CHECK (length(scope_ref) BETWEEN 1 AND 128),  -- a grant always has a scope (INV-REQ-859)
    period_from   date,
    period_to     date,
    valid_from    timestamptz NOT NULL,
    valid_until   timestamptz,
    status        text        NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    grant_source  text        NOT NULL CHECK (grant_source IN ('BY_HAND', 'DIRECTORY_GROUPS', 'SCIM')),
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, grant_ref),
    FOREIGN KEY (tenant_id, identity_ref) REFERENCES access.identity (tenant_id, identity_ref),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK ((scope_level = 'ENTITY_PERIOD') = (period_from IS NOT NULL AND period_to IS NOT NULL)),
    CHECK ((period_from IS NULL) = (period_to IS NULL)),
    CHECK (period_to IS NULL OR period_to >= period_from)
);
CREATE INDEX role_grant_identity ON access.role_grant (tenant_id, identity_ref);
-- The repeat key of grantRole (identity, role, scope) holds for grants not yet revoked
CREATE UNIQUE INDEX role_grant_repeat_key ON access.role_grant
    (tenant_id, identity_ref, role, scope_level, scope_ref, coalesce(period_from, date '0001-01-01'), coalesce(period_to, date '0001-01-01'))
    WHERE status <> 'REVOKED';

-- One monotonic authority sequence for every AuthorityChanged event (INV-API-3325, INV-REQ-1201 behaviour 1)
CREATE SEQUENCE access.authority_version_seq AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;

ALTER TABLE access.identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.identity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.identity
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.role_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.role_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.role_grant
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.identity TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.role_grant TO access_runtime;
GRANT USAGE ON SEQUENCE access.authority_version_seq TO access_runtime;
