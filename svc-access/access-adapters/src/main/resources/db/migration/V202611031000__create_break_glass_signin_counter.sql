SET lock_timeout = '5s';

-- V202611031000__create_break_glass_signin_counter.sql (INV-T-13465; INV-REQ-896, INV-AC-9184; INV-Q-9107 A;
-- CS-19, ARC-20). Run only by the service's migration Job as access_migrate (never at start-up). The back-off state of
-- the wrong sealing credentials at signInBreakGlass: one row per (tenant, unlock reference), written in the
-- transaction of the sign-in. `failures` counts the consecutive wrong credentials; `next_try_at` is the instant from
-- which the next try is allowed. The credential itself is never stored. The runtime login never deletes.

CREATE TABLE access.break_glass_signin_counter (
    tenant_id     uuid        NOT NULL,
    unlock_ref    text        NOT NULL,
    failures      integer     NOT NULL CHECK (failures >= 0),
    next_try_at   timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    PRIMARY KEY (tenant_id, unlock_ref)
);

ALTER TABLE access.break_glass_signin_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.break_glass_signin_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.break_glass_signin_counter
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.break_glass_signin_counter TO access_runtime;
