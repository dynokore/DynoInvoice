-- V202611051100: the exempt-purchase election of an entity (INV-REQ-369): the tax codes whose purchases are excluded
-- from submission and the recorded basis of certainty, effective-dated and append-only, with the acting user id (never
-- a role). Forced RLS on the signed binding as election_change; the runtime role may only read and insert.
SET lock_timeout = '5s';

CREATE TABLE tenancy.exempt_purchase_election (
    tenant_id      uuid   NOT NULL,
    id             bigint GENERATED ALWAYS AS IDENTITY,
    entity_ref     text   NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    effective_from date   NOT NULL,
    tax_codes      text[] NOT NULL CHECK (cardinality(tax_codes) BETWEEN 1 AND 50),
    basis          text   NOT NULL CHECK (length(basis) BETWEEN 1 AND 500 AND btrim(basis) <> ''),
    recorded_by    text   NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 128),
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX exempt_purchase_election_entity ON tenancy.exempt_purchase_election (tenant_id, entity_ref, effective_from);

ALTER TABLE tenancy.exempt_purchase_election ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.exempt_purchase_election FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.exempt_purchase_election
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.exempt_purchase_election TO tenancy_runtime;
