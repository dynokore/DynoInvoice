-- INV-T-4944 (INV-REQ-316, INV-REQ-830, INV-REQ-832, INV-REQ-833; INV-Q-9793, INV-Q-9797, INV-Q-9799, INV-Q-17291;
-- CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as masterdata_migrate (never at start-up). The three long commands
-- (gap report, import dry run, import) each insert their record as RUNNING in a committed transaction before the work
-- starts and finalise it SUCCEEDED or FAILED (API-17). The import record is the FR-AG.57 record: file hash, actor,
-- dry run, applied and failed counts and rejected rows. `import_id` on a counterparty version traces an imported value
-- to its import (INV-REQ-833 b2); the column is set by the apply only and stays null for every other write.

CREATE TABLE masterdata.gap_report (
    id            uuid        PRIMARY KEY,                                    -- UUIDv7, assigned by the application
    tenant_id     uuid        NOT NULL,                                       -- FR-G.5
    gap_report_id text        NOT NULL CHECK (length(gap_report_id) BETWEEN 1 AND 40),
    entity_ref    text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    actor_ref     text        NOT NULL CHECK (length(actor_ref) BETWEEN 1 AND 128), -- the token's principal (INV-Q-10647)
    state         text        NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    result        jsonb,                                                      -- rows and notChecked, SUCCEEDED only
    error         jsonb,                                                      -- the API-20 envelope, FAILED only
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, gap_report_id),
    CHECK ((state = 'SUCCEEDED') = (result IS NOT NULL) AND (state = 'FAILED') = (error IS NOT NULL)
        AND (state = 'RUNNING') = (completed_at IS NULL))
);

ALTER TABLE masterdata.gap_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.gap_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.gap_report
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.gap_report TO masterdata_runtime;

CREATE TABLE masterdata.counterparty_import_dry_run (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    dry_run_id    text        NOT NULL CHECK (length(dry_run_id) BETWEEN 1 AND 40),
    entity_ref    text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    actor_ref     text        NOT NULL CHECK (length(actor_ref) BETWEEN 1 AND 128),
    file_hash     text        NOT NULL CHECK (file_hash ~ '^[0-9a-f]{64}$'),  -- the caller's SHA-256, stored as given
    row_count     integer     NOT NULL CHECK (row_count BETWEEN 0 AND 5000),
    rows          jsonb       NOT NULL,                                       -- the stored rows the apply writes
    state         text        NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    result        jsonb,
    error         jsonb,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, dry_run_id),
    CHECK ((state = 'SUCCEEDED') = (result IS NOT NULL) AND (state = 'FAILED') = (error IS NOT NULL)
        AND (state = 'RUNNING') = (completed_at IS NULL))
);

ALTER TABLE masterdata.counterparty_import_dry_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.counterparty_import_dry_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.counterparty_import_dry_run
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.counterparty_import_dry_run TO masterdata_runtime;

CREATE TABLE masterdata.counterparty_import (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    import_id     text        NOT NULL CHECK (length(import_id) BETWEEN 1 AND 40),
    entity_ref    text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    actor_ref     text        NOT NULL CHECK (length(actor_ref) BETWEEN 1 AND 128), -- never from the request
    dry_run_id    text        NOT NULL CHECK (length(dry_run_id) BETWEEN 1 AND 40),
    file_hash     text        NOT NULL CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    provided_by   text        NOT NULL CHECK (provided_by IN ('MIGRATED', 'INTERNAL')),
    state         text        NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    applied_count integer     CHECK (applied_count >= 0),
    failed_count  integer     CHECK (failed_count >= 0),
    rejected_rows jsonb,
    record_hash   text        CHECK (record_hash ~ '^[0-9a-f]{64}$'),         -- SHA-256 of the published record bytes
    error         jsonb,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, import_id),
    UNIQUE (tenant_id, dry_run_id),                                           -- one record per reviewed dry run
    CHECK ((state = 'SUCCEEDED') = (applied_count IS NOT NULL AND failed_count IS NOT NULL
            AND rejected_rows IS NOT NULL AND record_hash IS NOT NULL)
        AND (state = 'FAILED') = (error IS NOT NULL)
        AND (state = 'RUNNING') = (completed_at IS NULL))
);

ALTER TABLE masterdata.counterparty_import ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.counterparty_import FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.counterparty_import
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.counterparty_import TO masterdata_runtime;

ALTER TABLE masterdata.counterparty_version
    ADD COLUMN import_id text CHECK (length(import_id) BETWEEN 1 AND 40);
