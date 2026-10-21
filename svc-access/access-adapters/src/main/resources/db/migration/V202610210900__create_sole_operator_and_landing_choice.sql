-- V202610210900__create_sole_operator_and_landing_choice.sql (INV-T-4935; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: an acknowledgement is withdrawn by status, a landing choice is cleared by setting its role to null.

-- The sole-operator acknowledgement of INV-REQ-994 (INV-REQ-891): one active row per tenant at most (repeatKey tenantRef).
CREATE TABLE access.sole_operator_ack (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    ack_ref               text        NOT NULL CHECK (length(ack_ref) BETWEEN 1 AND 64),
    compensating_controls text        NOT NULL CHECK (length(compensating_controls) BETWEEN 1 AND 1000),
    status                text        NOT NULL CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    created_at            timestamptz NOT NULL,
    created_by            text        NOT NULL,
    updated_at            timestamptz NOT NULL,
    updated_by            text        NOT NULL,
    version               bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, ack_ref)
);
CREATE UNIQUE INDEX sole_operator_ack_one_active ON access.sole_operator_ack (tenant_id) WHERE status = 'ACTIVE';

-- A person's own landing screen choice (INV-REQ-2756 behaviour 3): a per-user setting, not a variation point.
CREATE TABLE access.landing_choice (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    identity_ref  text        NOT NULL CHECK (length(identity_ref) BETWEEN 1 AND 64),
    role          text        CHECK (role IS NULL OR length(role) BETWEEN 1 AND 64),   -- null: the choice is cleared
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, identity_ref)
);

ALTER TABLE access.sole_operator_ack ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.sole_operator_ack FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.sole_operator_ack
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.landing_choice ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.landing_choice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.landing_choice
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.sole_operator_ack TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.landing_choice TO access_runtime;
