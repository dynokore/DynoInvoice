-- V202611031000: the recorded IRAS extensions of period records (INV-REQ-81, INV-REQ-86), the replica of the filing
-- status of a period that submission-svc publishes (INV-REQ-85, INV-Q-16113) and the one writable column of a period
-- record that an extension or a recomputation sets (INV-REQ-1323, INV-REQ-88). Tenant-scoped with forced RLS on the
-- signed binding (INV-REQ-854 b1). An extension is insert-only. Run by tenancy_migrate.
SET lock_timeout = '5s';

-- One row per recorded extension: the target, the scope, the filing due date it replaced and the new absolute dates,
-- the IRAS reference and the evidence reference, the recording user and the time.
CREATE TABLE tenancy.period_extension (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    entity_id                 uuid        NOT NULL,
    period_ref                text        NOT NULL CHECK (length(period_ref) BETWEEN 1 AND 32),
    target                    text        NOT NULL CHECK (target IN ('FILING_DUE_DATE', 'SUBMISSION_DUE_DATE', 'BOTH')),
    scope_kind                text        NOT NULL CHECK (scope_kind IN ('PERIOD', 'DOCUMENT_SET', 'DATE_RANGE')),
    document_refs             text        NOT NULL,
    scope_date_from           date,
    scope_date_to             date,
    original_filing_due_date  date,
    new_filing_due_date       date,
    new_submission_due_date   date,
    iras_reference            text        NOT NULL CHECK (length(iras_reference) BETWEEN 1 AND 64),
    evidence_ref              text        NOT NULL CHECK (length(evidence_ref) BETWEEN 1 AND 64),
    recorded_by               text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 200),
    recorded_at               timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id),
    CHECK ((target = 'SUBMISSION_DUE_DATE') = (new_filing_due_date IS NULL)),
    CHECK ((target = 'FILING_DUE_DATE') = (new_submission_due_date IS NULL)),
    CHECK ((new_filing_due_date IS NULL) = (original_filing_due_date IS NULL))
);

CREATE UNIQUE INDEX period_extension_reference
    ON tenancy.period_extension (tenant_id, entity_id, period_ref, iras_reference);

-- The latest PeriodFilingStatusChanged per entity and period: the event with the greatest recorded_at is kept.
CREATE TABLE tenancy.period_filing_fact (
    tenant_id           uuid        NOT NULL,
    entity_ref          text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    period_ref          text        NOT NULL CHECK (length(period_ref) BETWEEN 1 AND 64),
    submission_late     boolean     NOT NULL,
    governing_filed_at  timestamptz,
    conflict            boolean     NOT NULL,
    recorded_at         timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, entity_ref, period_ref)
);

ALTER TABLE tenancy.period_extension ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.period_extension FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.period_extension
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.period_filing_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.period_filing_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.period_filing_fact
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.period_extension TO tenancy_runtime;
GRANT SELECT, INSERT ON tenancy.period_filing_fact TO tenancy_runtime;
GRANT UPDATE (submission_late, governing_filed_at, conflict, recorded_at) ON tenancy.period_filing_fact TO tenancy_runtime;
GRANT UPDATE (due_date) ON tenancy.period_record TO tenancy_runtime;
