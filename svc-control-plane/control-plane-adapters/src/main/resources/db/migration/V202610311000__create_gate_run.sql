-- V202610311000__create_gate_run.sql (TPL-12, CS-19, INV-T-8749, INV-REQ-2657, INV-Q-8717 A, INV-Q-10647)
-- The gate results of a release train, recorded by recordGateRun: one row per train, gate and environment, append-only.
-- PLATFORM data (no tenant column). recorded_by is the user ID of the caller, never a role name. The runtime login
-- reads and appends; the service never updates or deletes a row.

CREATE TABLE controlplane.gate_run (
    id                 uuid        PRIMARY KEY,
    train_version      text        NOT NULL CHECK (length(train_version) BETWEEN 1 AND 32),
    gate               text        NOT NULL CHECK (gate IN ('SCENARIOS', 'REGENERATION', 'SECURITY', 'CERTIFICATION')),
    environment        text        CHECK (environment IN ('SAAS', 'PRIVATE_MINIMAL')),
    candidate_result   text        NOT NULL CHECK (candidate_result IN ('PASSED', 'FAILED')),
    baseline_result    text        CHECK (baseline_result IN ('PASSED', 'FAILED')),
    candidate_findings text[]      NOT NULL CHECK (cardinality(candidate_findings) <= 500),
    baseline_findings  text[]      NOT NULL CHECK (cardinality(baseline_findings) <= 500),
    evidence_ref       text        CHECK (length(evidence_ref) <= 256),
    recorded_by        text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 64),
    recorded_at        timestamptz NOT NULL
);
CREATE UNIQUE INDEX gate_run_key ON controlplane.gate_run (train_version, gate, coalesce(environment, '-'));
COMMENT ON TABLE controlplane.gate_run IS 'platform-scoped: no tenant data';

GRANT SELECT, INSERT ON controlplane.gate_run TO controlplane_runtime;
