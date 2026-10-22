-- V202610221000: the effective-dated status attributes of a registered entity (INV-REQ-331, INV-REQ-322, INV-REQ-333,
-- INV-REQ-335). One row per (entity, status kind, effective date): a change opens a new effective period and the earlier
-- row stays readable (history). Run by tenancy_migrate. Forced RLS on the signed binding (INV-REQ-854 b1).
SET lock_timeout = '5s';

CREATE TABLE tenancy.entity_status (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    entity_id                 uuid        NOT NULL,
    status_kind               text        NOT NULL CHECK (status_kind IN ('EXCLUDED_BUSINESS', 'PARTIAL_EXEMPTION', 'OBLIGATION_START')),
    effective_from            date        NOT NULL,
    partially_exempt          boolean,
    excluded_business_status  text        CHECK (excluded_business_status IN ('NOT_EXCLUDED', 'OVERSEAS_S33_1', 'OVR', 'RC_ONLY')),
    basis                     text        CHECK (basis IS NULL OR length(basis) BETWEEN 1 AND 500),
    evidence_ref              text        CHECK (evidence_ref IS NULL OR length(evidence_ref) BETWEEN 1 AND 200),
    obligation_start_date     date,
    obligation_start_basis    text        CHECK (obligation_start_basis IN ('MANDATORY_DATE', 'EARLIER_VOLUNTARY_DATE', 'IRAS_COMMUNICATED_DATE')),
    iras_reference            text        CHECK (iras_reference IS NULL OR length(iras_reference) BETWEEN 1 AND 100),
    obligation_start_override boolean,
    registration_start_date   date,
    recorded_at               timestamptz NOT NULL,
    UNIQUE (tenant_id, entity_id, status_kind, effective_from),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id),
    CHECK (status_kind <> 'PARTIAL_EXEMPTION' OR (partially_exempt IS NOT NULL AND basis IS NOT NULL)),
    CHECK (status_kind <> 'EXCLUDED_BUSINESS' OR (excluded_business_status IS NOT NULL AND basis IS NOT NULL AND evidence_ref IS NOT NULL)),
    CHECK (status_kind <> 'OBLIGATION_START' OR (obligation_start_date IS NOT NULL AND obligation_start_basis IS NOT NULL)),
    CHECK (obligation_start_basis IS DISTINCT FROM 'IRAS_COMMUNICATED_DATE' OR iras_reference IS NOT NULL)
);

CREATE INDEX entity_status_entity ON tenancy.entity_status (tenant_id, entity_id, status_kind, effective_from);

ALTER TABLE tenancy.entity_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.entity_status FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.entity_status
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.entity_status TO tenancy_runtime;
