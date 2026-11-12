-- The per-period counter behind dueDateVersion of PeriodDueDateChanged (INV-REQ-88, INV-AC-20668). It lives outside
-- period_record because a superseded record can be re-created for the same reference and the counter must keep counting.
-- Expand-only: one new table, nothing existing is altered.
SET lock_timeout = '5s';

CREATE TABLE tenancy.period_due_date_version (
    tenant_id  uuid   NOT NULL,
    entity_id  uuid   NOT NULL,
    period_ref text   NOT NULL CHECK (length(period_ref) BETWEEN 1 AND 32),
    version    bigint NOT NULL CHECK (version >= 1),
    PRIMARY KEY (tenant_id, entity_id, period_ref),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

ALTER TABLE tenancy.period_due_date_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.period_due_date_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.period_due_date_version
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.period_due_date_version TO tenancy_runtime;
GRANT UPDATE (version) ON tenancy.period_due_date_version TO tenancy_runtime;
