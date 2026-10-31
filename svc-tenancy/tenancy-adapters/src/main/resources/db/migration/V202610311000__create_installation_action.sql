-- V202610311000: the installer run-record steps tenancy-svc has recorded (INV-T-3290; INV-REQ-1101 b5, INV-REQ-861,
-- coordinator ruling INV-Q-3245 A). Installation-scoped, not tenant data. Append-only: one row per (run id, step),
-- written once, never updated or deleted; the primary key is the permanent idempotency key of recordInstallationAction.
SET lock_timeout = '5s';

CREATE TABLE tenancy.installation_action (
    run_id              uuid        NOT NULL,
    step                integer     NOT NULL CHECK (step >= 1),
    command             text        NOT NULL CHECK (command IN ('INSTALL', 'UPGRADE', 'PATCH_TRAIN_APPLY', 'RESTORE',
                                                               'PROVISIONING', 'HEARTBEAT_TARGET_CHANGE',
                                                               'SIEM_TARGET_CHANGE', 'SUPPORT_BUNDLE_EXPORT',
                                                               'ROTATE_DB_CREDENTIAL')),
    step_name           text        NOT NULL CHECK (length(step_name) BETWEEN 1 AND 128),
    outcome             text        NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'SKIPPED')),
    occurred_at         timestamptz NOT NULL,
    run_identity        text        NOT NULL CHECK (length(run_identity) BETWEEN 1 AND 128),
    deployment_operator text        NOT NULL CHECK (length(deployment_operator) BETWEEN 1 AND 128),
    approving_engineer  text        CHECK (length(approving_engineer) BETWEEN 1 AND 128),
    train_from          text        CHECK (length(train_from) BETWEEN 1 AND 64),
    train_to            text        CHECK (length(train_to) BETWEEN 1 AND 64),
    fingerprint         text        NOT NULL CHECK (length(fingerprint) = 64),
    audit_entry_id      uuid        NOT NULL,
    recorded_at         timestamptz NOT NULL,
    PRIMARY KEY (run_id, step)
);
COMMENT ON TABLE tenancy.installation_action IS 'platform-scoped: no tenant data';
GRANT SELECT, INSERT ON tenancy.installation_action TO tenancy_runtime;
