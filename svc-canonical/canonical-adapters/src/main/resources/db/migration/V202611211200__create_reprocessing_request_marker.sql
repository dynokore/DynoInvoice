-- V202611211200__create_reprocessing_request_marker.sql (INV-T-24003; INV-REQ-1003, ruling of the coder stop of
-- INV-T-24003 item 3). One row per (tenant, reprocessing run) whose PackReprocessingRequested was answered: a
-- redelivery adds no row and no page.
CREATE TABLE canonical.reprocessing_request_marker (
    tenant_id uuid NOT NULL,
    reprocessing_id text NOT NULL CHECK (length(reprocessing_id) BETWEEN 1 AND 40),
    answered_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, reprocessing_id)
);
ALTER TABLE canonical.reprocessing_request_marker ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.reprocessing_request_marker FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.reprocessing_request_marker
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT ON canonical.reprocessing_request_marker TO canonical_runtime;
