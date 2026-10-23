-- V202610230900__create_idp_mapping_and_break_glass.sql (INV-T-4938; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: a mapping is removed by status, a break-glass session is closed by status.

-- The grant a directory-group mapping made points back at it (INV-REQ-892 behaviour 3)
ALTER TABLE access.role_grant
    ADD COLUMN mapping_ref text CHECK (mapping_ref IS NULL OR length(mapping_ref) BETWEEN 1 AND 64);
CREATE INDEX role_grant_mapping ON access.role_grant (tenant_id, mapping_ref) WHERE mapping_ref IS NOT NULL;

-- A directory group mapped to a role x scope grant with its validity window (INV-REQ-892, INV-REQ-1263, mapIdpGroup)
CREATE TABLE access.idp_group_mapping (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    mapping_ref   text        NOT NULL CHECK (length(mapping_ref) BETWEEN 1 AND 64),
    idp_group     text        NOT NULL CHECK (length(idp_group) BETWEEN 1 AND 128),
    role          text        NOT NULL CHECK (length(role) BETWEEN 1 AND 64),
    scope_level   text        NOT NULL CHECK (scope_level IN ('TENANT', 'ENTITY')),
    scope_ref     text        NOT NULL CHECK (length(scope_ref) BETWEEN 1 AND 128),
    valid_from    date        NOT NULL,
    valid_to      date,
    status        text        NOT NULL CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, mapping_ref),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
-- The repeat key of mapIdpGroup (idpGroup, role, scope) holds for mappings not yet removed
CREATE UNIQUE INDEX idp_group_mapping_repeat_key ON access.idp_group_mapping
    (tenant_id, idp_group, role, scope_level, scope_ref) WHERE status = 'ACTIVE';

-- An unlock of the sealed break-glass account by two people (INV-REQ-896, INV-REQ-1269, unlockBreakGlass)
CREATE TABLE access.break_glass_session (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    unlock_ref    text        NOT NULL CHECK (length(unlock_ref) BETWEEN 1 AND 64),
    session_hours integer     NOT NULL CHECK (session_hours BETWEEN 1 AND 8),
    unlocked_at   timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    approved_by   text[]      NOT NULL CHECK (cardinality(approved_by) BETWEEN 2 AND 3),
    status        text        NOT NULL CHECK (status IN ('UNLOCKED', 'SEALED')),
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, unlock_ref),
    CHECK (expires_at > unlocked_at)
);

-- One action carried out under an open session (INV-REQ-897, recordBreakGlassAction)
CREATE TABLE access.break_glass_action (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    action_ref    text        NOT NULL CHECK (length(action_ref) BETWEEN 1 AND 64),
    unlock_ref    text        NOT NULL,
    action        text        NOT NULL CHECK (action IN ('VIEW', 'READINESS', 'DRAIN', 'REPLAY')),
    target        text        CHECK (target IS NULL OR length(target) BETWEEN 1 AND 256),
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, action_ref),
    FOREIGN KEY (tenant_id, unlock_ref) REFERENCES access.break_glass_session (tenant_id, unlock_ref)
);

ALTER TABLE access.idp_group_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.idp_group_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.idp_group_mapping
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.break_glass_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.break_glass_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.break_glass_session
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.break_glass_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.break_glass_action FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.break_glass_action
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.idp_group_mapping TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.break_glass_session TO access_runtime;
GRANT SELECT, INSERT ON access.break_glass_action TO access_runtime;
