-- V202611011100__create_break_glass_approval.sql (INV-T-9314; INV-Q-9109 A; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: an approval is replaced in place while unconsumed and closed by consumed_at.

-- One approval of a break-glass unlock by an approver other than the unlocking caller (approveBreakGlassUnlock);
-- unlockBreakGlass consumes it in the transaction that inserts the session.
CREATE TABLE access.break_glass_approval (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    approval_ref        text        NOT NULL CHECK (length(approval_ref) BETWEEN 1 AND 64),
    approver_ref        text        NOT NULL CHECK (length(approver_ref) BETWEEN 1 AND 128),
    made_at             timestamptz NOT NULL,
    consumed_at         timestamptz,
    consumed_unlock_ref text        CHECK (consumed_unlock_ref IS NULL OR length(consumed_unlock_ref) BETWEEN 1 AND 64),
    created_at          timestamptz NOT NULL,
    created_by          text        NOT NULL,
    updated_at          timestamptz NOT NULL,
    updated_by          text        NOT NULL,
    version             bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, approval_ref),
    CHECK ((consumed_at IS NULL) = (consumed_unlock_ref IS NULL))
);

-- One unconsumed approval per approver and tenant
CREATE UNIQUE INDEX break_glass_approval_unconsumed ON access.break_glass_approval (tenant_id, approver_ref)
    WHERE consumed_at IS NULL;

ALTER TABLE access.break_glass_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.break_glass_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.break_glass_approval
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.break_glass_approval TO access_runtime;
