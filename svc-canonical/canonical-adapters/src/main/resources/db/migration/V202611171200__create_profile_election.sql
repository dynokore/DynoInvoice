-- V202611171200__create_profile_election.sql (INV-T-21464; INV-REQ-479, ruling INV-Q-21422 A).
CREATE TABLE canonical.profile_election (
    tenant_id uuid NOT NULL,
    entity_ref text NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    attribute text NOT NULL CHECK (attribute = 'purchaseDateBasis' OR attribute ~ '^gstScheme\.[A-Z0-9_-]{1,40}$'),
    effective_date date NOT NULL,
    value text NOT NULL CHECK (length(value) BETWEEN 1 AND 40 AND (attribute <> 'purchaseDateBasis' OR value IN ('INVOICE_DATE', 'POSTING_DATE'))),
    version bigint NOT NULL CHECK (version >= 1),
    PRIMARY KEY (tenant_id, entity_ref, attribute, effective_date)
);
ALTER TABLE canonical.profile_election ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.profile_election FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.profile_election
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON canonical.profile_election TO canonical_runtime;
