-- V202611211000__create_sample_request_marker.sql (INV-T-4916; INV-REQ-772, ruling "dry-run marker" option A).
-- One row per (tenant, dry run) whose PackDryRunSampleRequested was answered: a redelivery adds no row and no page.
CREATE TABLE canonical.sample_request_marker (
    tenant_id uuid NOT NULL,
    dry_run_id text NOT NULL CHECK (length(dry_run_id) BETWEEN 1 AND 40),
    answered_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, dry_run_id)
);
ALTER TABLE canonical.sample_request_marker ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.sample_request_marker FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.sample_request_marker
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT ON canonical.sample_request_marker TO canonical_runtime;
