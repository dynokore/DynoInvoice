-- create accreditation_task (TPL-12, CS-19, INV-T-7323, INV-REQ-1365, INV-REQ-2657, INV-AC-2340)
-- The tracked re-accreditation assessment of a release. PLATFORM data (no tenant column), declared platform-scoped by
-- the table comment. A task is never a release gate. The runtime login reads, inserts and, to conclude a task, updates
-- the three conclusion columns only. One task per (release_ref, reason): a repeat of a release opens no second one.

CREATE TABLE controlplane.accreditation_task (
    task_ref     text        PRIMARY KEY CHECK (length(task_ref) BETWEEN 1 AND 128),
    status       text        NOT NULL CHECK (status IN ('OPEN', 'CONCLUDED')),
    reason       text        NOT NULL CHECK (reason IN ('ACCREDITATION_LAPSED', 'REASSESSMENT_NEEDED')),
    opened_at    timestamptz NOT NULL,
    alert_ref    text        NOT NULL CHECK (length(alert_ref) BETWEEN 1 AND 128),
    release_ref  text        CHECK (length(release_ref) BETWEEN 1 AND 128),
    concluded_at timestamptz,
    conclusion   text        CHECK (length(conclusion) BETWEEN 1 AND 2000),
    CHECK ((status = 'OPEN') = (concluded_at IS NULL AND conclusion IS NULL)),
    CHECK ((concluded_at IS NULL) = (conclusion IS NULL)),
    UNIQUE (release_ref, reason)
);
COMMENT ON TABLE controlplane.accreditation_task IS 'platform-scoped: no tenant data';
CREATE INDEX accreditation_task_order ON controlplane.accreditation_task (opened_at DESC, task_ref ASC);

GRANT SELECT, INSERT ON controlplane.accreditation_task TO controlplane_runtime;
GRANT UPDATE (status, concluded_at, conclusion) ON controlplane.accreditation_task TO controlplane_runtime;

-- The release record holds the check of its own release (INV-T-7323): the pack version's record gains the columns the
-- train row already has; the runtime login may set the three of a train row once the task step has run.
ALTER TABLE controlplane.pack_release_record
    ADD COLUMN needs_reaccreditation boolean NOT NULL DEFAULT false,
    ADD COLUMN alert_ref text CHECK (length(alert_ref) BETWEEN 1 AND 128),
    ADD COLUMN task_ref  text CHECK (length(task_ref) BETWEEN 1 AND 128);

GRANT UPDATE (needs_reaccreditation, alert_ref, task_ref) ON controlplane.release_train TO controlplane_runtime;
