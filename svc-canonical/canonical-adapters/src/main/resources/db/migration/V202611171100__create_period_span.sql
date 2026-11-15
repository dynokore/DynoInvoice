-- V202611171100__create_period_span.sql (INV-T-21429; INV-REQ-479, ruling INV-Q-21406 A).
CREATE TABLE canonical.period_span_version (
    tenant_id uuid NOT NULL,
    entity_ref text NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    version bigint NOT NULL CHECK (version >= 1),
    PRIMARY KEY (tenant_id, entity_ref)
);
ALTER TABLE canonical.period_span_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.period_span_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.period_span_version
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON canonical.period_span_version TO canonical_runtime;
CREATE TABLE canonical.period_span (
    tenant_id uuid NOT NULL,
    entity_ref text NOT NULL,
    period_ref text NOT NULL CHECK (length(btrim(period_ref)) BETWEEN 1 AND 64),
    period_start date NOT NULL,
    period_end date NOT NULL CHECK (period_end >= period_start),
    PRIMARY KEY (tenant_id, entity_ref, period_ref)
);
ALTER TABLE canonical.period_span ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.period_span FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.period_span
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT, DELETE ON canonical.period_span TO canonical_runtime;
