-- V202611201000: the tenant-isolated replica of the UnsubmittedDocumentCountMeasured event of submission-svc
-- (INV-REQ-956, INV-AC-23029). One row per tenant holds the event with the greatest measured_at (event-carried state
-- transfer); received_at is the consumer's own clock at the moment it applied the event. The handler replaces a row
-- only for a strictly later measured_at. tenancy_runtime holds SELECT, INSERT and UPDATE only, no DELETE. No actor is
-- stored. Forced RLS as the other tenancy tables. Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.unsubmitted_count_replica (
    tenant_id         uuid        NOT NULL PRIMARY KEY,
    measured_at       timestamptz NOT NULL,
    received_at       timestamptz NOT NULL,
    unsubmitted_total integer     NOT NULL CHECK (unsubmitted_total >= 0),
    entities          jsonb       NOT NULL
);

ALTER TABLE tenancy.unsubmitted_count_replica ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.unsubmitted_count_replica FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.unsubmitted_count_replica
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.unsubmitted_count_replica TO tenancy_runtime;
