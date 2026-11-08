-- V202611091100: the offboarding record (INV-REQ-877, INV-REQ-956; INV-T-16413): one row per request, with the named
-- retention custody holder, the export manifest the export port answered, the unsubmitted-document override and the
-- handover statement reference. A record is never updated: a FAILED record stays as history (INV-Q-5025).
-- Run by tenancy_migrate. Forced RLS on the signed binding (INV-REQ-854 b1). The requester and the second confirmer are
-- user IDs, never roles (INV-Q-10647).
SET lock_timeout = '5s';

CREATE TABLE tenancy.offboarding_record (
    tenant_id                  uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    offboarding_ref            text        NOT NULL CHECK (length(offboarding_ref) BETWEEN 1 AND 64),
    idempotency_key            text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 64),
    scope                      text        NOT NULL CHECK (scope IN ('ENTITY', 'TENANT')),
    entity_ref                 text        CHECK (length(entity_ref) BETWEEN 1 AND 64),
    state                      text        NOT NULL CHECK (state IN ('REQUESTED', 'COMPLETED', 'FAILED')),
    custody_holder_name        text        NOT NULL CHECK (length(custody_holder_name) <= 200),
    custody_holder_email       text        NOT NULL CHECK (length(custody_holder_email) <= 254),
    custody_holder_mobile      text,
    export_ref                 text        NOT NULL CHECK (length(export_ref) BETWEEN 1 AND 64),
    export_state               text        NOT NULL CHECK (export_state IN ('REQUESTED', 'READY', 'FAILED')),
    export_contents            text[]      NOT NULL,
    override_acknowledged_by   text        CHECK (length(override_acknowledged_by) <= 128),
    override_statement         text        CHECK (length(override_statement) <= 1000),
    notice_window_days         integer     CHECK (notice_window_days >= 1),
    handover_statement_ref     text        CHECK (length(handover_statement_ref) <= 200),
    requested_by               text        CHECK (length(requested_by) BETWEEN 1 AND 256),
    second_confirmation_by     text        CHECK (length(second_confirmation_by) BETWEEN 1 AND 128),
    created_at                 timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, offboarding_ref),
    UNIQUE (tenant_id, idempotency_key),
    CHECK ((scope = 'ENTITY') = (entity_ref IS NOT NULL)),
    CHECK ((override_acknowledged_by IS NULL) = (override_statement IS NULL))
);

-- TNC-010: at most one REQUESTED or COMPLETED record per tenant, scope and entity; a FAILED one does not block.
CREATE UNIQUE INDEX offboarding_record_open_one
    ON tenancy.offboarding_record (tenant_id, scope, coalesce(entity_ref, ''))
    WHERE state <> 'FAILED';

ALTER TABLE tenancy.offboarding_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.offboarding_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.offboarding_record
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.offboarding_record TO tenancy_runtime;
