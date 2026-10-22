-- V202610221100: the stored result of assessPhase with every input used (INV-REQ-334 behaviour 5). One row per call:
-- the latest row of an entity is its assessment, the earlier rows stay as history. Its mandatory date is the bound of
-- an earlier voluntary implementation date (INV-REQ-1312, INV-AC-2300). Run by tenancy_migrate. Forced RLS on the
-- signed binding (INV-REQ-854 b1).
SET lock_timeout = '5s';

CREATE TABLE tenancy.phase_assessment (
    id                     uuid          PRIMARY KEY,
    tenant_id              uuid          NOT NULL,
    entity_id              uuid          NOT NULL,
    seq                    bigint        GENERATED ALWAYS AS IDENTITY,
    as_of_date             date          NOT NULL,
    total_annual_supplies  numeric(19,2) NOT NULL,
    mandatory_date         date          NOT NULL,
    phase                  text          NOT NULL CHECK (phase IN ('PHASE_1', 'PHASE_2', 'PHASE_3', 'PHASE_4', 'PHASE_5', 'PHASE_6')),
    window_row             text          CHECK (window_row IN ('JAN_TO_DEC', 'NOV_TO_OCT', 'DEC_TO_NOV')),
    days_covered           integer       CHECK (days_covered IS NULL OR days_covered >= 1),
    extrapolated           boolean       NOT NULL,
    summed_box4            numeric(19,2) NOT NULL,
    returns_used           text          NOT NULL,
    recorded_at            timestamptz   NOT NULL,
    UNIQUE (tenant_id, seq),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

CREATE INDEX phase_assessment_entity ON tenancy.phase_assessment (tenant_id, entity_id, seq DESC);

ALTER TABLE tenancy.phase_assessment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.phase_assessment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.phase_assessment
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.phase_assessment TO tenancy_runtime;
