-- V202611211100__create_submission_acknowledgement.sql (INV-T-25749; INV-REQ-1003, ruling of INV-T-24003 option B).
-- Event-carried state transfer: one row per SubmissionAcknowledged event of a tenant, keyed by (tenant, Doc UUID,
-- version). An acknowledgement is a fact: SELECT and INSERT only, no UPDATE, no DELETE. No foreign key to
-- canonical.document: the event carries no partition date or entity id.
CREATE TABLE canonical.submission_acknowledgement (
    tenant_id uuid NOT NULL,
    doc_uuid uuid NOT NULL,
    version integer NOT NULL CHECK (version >= 1),
    acknowledgement_id text NOT NULL CHECK (length(btrim(acknowledgement_id)) >= 1),
    acknowledged_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, doc_uuid, version)
);
ALTER TABLE canonical.submission_acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.submission_acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.submission_acknowledgement
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT ON canonical.submission_acknowledgement TO canonical_runtime;
