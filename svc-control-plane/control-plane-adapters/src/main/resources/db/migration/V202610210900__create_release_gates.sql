-- V202610210900__create_release_gates.sql (TPL-12, CS-19, INV-T-4985, INV-REQ-909, INV-REQ-2657, INV-REQ-2660)
-- Release trains, the supported-version window and the release records of pack versions. All three tables are PLATFORM
-- data (no tenant column), each declared platform-scoped by its table comment. The runtime login reads and appends: a
-- published train and a recorded window are final (a later train supersedes, never unpublishes). The release record of
-- a pack version is the one row the runtime login may also update: the staging run joins the corpus run at publication.

CREATE TABLE controlplane.release_train (
    train_version         text        PRIMARY KEY CHECK (length(train_version) BETWEEN 1 AND 32),
    kind                  text        NOT NULL CHECK (kind IN ('TRAIN', 'PATCH')),
    exposure_reducing     boolean     NOT NULL,
    needs_reaccreditation boolean     NOT NULL,
    -- the gate runs, candidate and released-version results with their findings, as JSON text (INV-AC-2658)
    runs                  text        NOT NULL CHECK (runs::jsonb IS NOT NULL),
    classified_by         text        NOT NULL CHECK (length(classified_by) BETWEEN 1 AND 64),
    alert_ref             text        CHECK (length(alert_ref) BETWEEN 1 AND 128),
    task_ref              text        CHECK (length(task_ref) BETWEEN 1 AND 128),
    recorded_at           timestamptz NOT NULL
);
COMMENT ON TABLE controlplane.release_train IS 'platform-scoped: no tenant data';

CREATE TABLE controlplane.support_window (
    window_version text        PRIMARY KEY CHECK (length(window_version) BETWEEN 1 AND 16),
    train_versions text[]      NOT NULL CHECK (cardinality(train_versions) <= 20),
    recorded_at    timestamptz NOT NULL
);
COMMENT ON TABLE controlplane.support_window IS 'platform-scoped: no tenant data';

CREATE TABLE controlplane.pack_release_record (
    pack_ref           text        PRIMARY KEY REFERENCES controlplane.pack_version (pack_ref),
    exposure_reducing  boolean     NOT NULL,
    runs               text        NOT NULL CHECK (runs::jsonb IS NOT NULL),
    classified_by      text        NOT NULL CHECK (length(classified_by) BETWEEN 1 AND 64),
    recorded_at        timestamptz NOT NULL
);
COMMENT ON TABLE controlplane.pack_release_record IS 'platform-scoped: no tenant data';

GRANT SELECT, INSERT ON controlplane.release_train TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.support_window TO controlplane_runtime;
GRANT SELECT, INSERT, UPDATE ON controlplane.pack_release_record TO controlplane_runtime;
