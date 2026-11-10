-- create tenant_schedule (TPL-12, CS-19, INV-T-8143, INV-REQ-8100, INV-BR-1894)
-- The go-live and offboarding schedule of a tenant. PLATFORM data (the operator's, not a tenant's): no tenant column,
-- declared platform-scoped by the table comment. The runtime login reads, inserts and updates it; a row is never
-- deleted. One live (not CANCELLED) schedule per tenant. A change or a cancellation waits in the pending_* columns
-- and the approved columns stay in force until the approval moves it.

CREATE TABLE controlplane.tenant_schedule (
    schedule_ref         text        PRIMARY KEY CHECK (length(schedule_ref) BETWEEN 1 AND 64),
    tenant_ref           text        NOT NULL CHECK (length(tenant_ref) BETWEEN 1 AND 64),
    contract_reference   text        NOT NULL CHECK (length(contract_reference) BETWEEN 1 AND 64),
    state                text        NOT NULL CHECK (state IN ('AWAITING_APPROVAL', 'APPROVED', 'EXECUTED', 'FAILED', 'CANCELLED')),
    go_live_at           timestamptz NOT NULL,
    offboarding_at       timestamptz NOT NULL,
    set_by               text        NOT NULL CHECK (length(set_by) BETWEEN 1 AND 128),
    set_at               timestamptz NOT NULL,
    approved_by          text        CHECK (length(approved_by) BETWEEN 1 AND 128),
    custody_name         text        NOT NULL CHECK (length(custody_name) BETWEEN 1 AND 200),
    custody_email        text        NOT NULL CHECK (length(custody_email) BETWEEN 1 AND 254),
    custody_mobile       text        CHECK (length(custody_mobile) BETWEEN 1 AND 64),
    notice_window_days   integer     CHECK (notice_window_days >= 1),
    opened_at            timestamptz,
    last_open_attempt_at timestamptz,
    pending_change_go_live_at     timestamptz,
    pending_change_offboarding_at timestamptz,
    pending_change_by    text        CHECK (length(pending_change_by) BETWEEN 1 AND 128),
    pending_cancellation_by text     CHECK (length(pending_cancellation_by) BETWEEN 1 AND 128),
    CHECK (offboarding_at > go_live_at),
    CHECK ((state = 'AWAITING_APPROVAL') = (approved_by IS NULL)),
    CHECK ((pending_change_go_live_at IS NULL AND pending_change_offboarding_at IS NULL) = (pending_change_by IS NULL))
);
COMMENT ON TABLE controlplane.tenant_schedule IS 'platform-scoped: no tenant data';
CREATE UNIQUE INDEX tenant_schedule_live ON controlplane.tenant_schedule (tenant_ref) WHERE state <> 'CANCELLED';
CREATE INDEX tenant_schedule_due ON controlplane.tenant_schedule (go_live_at) WHERE state = 'APPROVED' AND opened_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON controlplane.tenant_schedule TO controlplane_runtime;
