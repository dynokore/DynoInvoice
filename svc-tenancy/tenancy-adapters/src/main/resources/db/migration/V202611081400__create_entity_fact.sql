-- V202611081400: the entity-level facts of the onboarding questionnaire (INV-REQ-987; owner answer INV-Q-9696 B), one row
-- per tenant and entity. A NULL fact is "not recorded"; a call replaces only the facts it carries. The row keeps the
-- verified caller and the time of the last write.
-- Run by tenancy_migrate. Forced RLS on the signed binding (INV-REQ-854 b1). Keyed by tenant and entity (INV-AC-2041).
SET lock_timeout = '5s';

CREATE TABLE tenancy.entity_fact (
    tenant_id                        uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    entity_ref                       text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    records_purchases                boolean,
    has_foreign_currency_documents   boolean,
    claims_input_tax_on_posting_basis boolean,
    recorded_by                      text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 256),
    recorded_at                      timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, entity_ref)
);

ALTER TABLE tenancy.entity_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.entity_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.entity_fact
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.entity_fact TO tenancy_runtime;
