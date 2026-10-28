-- V202610281010: the pending change of an election (INV-REQ-370, INV-REQ-422, INV-REQ-505, INV-REQ-1284; owner answer
-- INV-Q-10105: a two-step flow). A change of an election already recorded is stored here as PENDING and takes effect
-- only when another person holding election-change.approve approves it; the version is then written through the
-- policy writer and the row turns APPROVED. Forced RLS on the signed binding.
SET lock_timeout = '5s';

CREATE TABLE tenancy.election_change (
    tenant_id       uuid        NOT NULL,
    change_ref      text        NOT NULL CHECK (length(change_ref) BETWEEN 1 AND 64),
    entity_ref      text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    variation_point text        NOT NULL CHECK (variation_point IN ('VP-22', 'VP-23', 'VP-24')),
    requested_value text        NOT NULL CHECK (length(requested_value) BETWEEN 1 AND 200),
    requested_basis text        CHECK (length(requested_basis) BETWEEN 1 AND 32),
    effective_from  date        NOT NULL,
    requested_by    text        NOT NULL CHECK (length(requested_by) BETWEEN 1 AND 128),
    requested_at    timestamptz NOT NULL,
    state           text        NOT NULL CHECK (state IN ('PENDING', 'APPROVED')),
    approved_by     text        CHECK (length(approved_by) BETWEEN 1 AND 128),
    approved_at     timestamptz,
    PRIMARY KEY (tenant_id, change_ref),
    CHECK ((variation_point = 'VP-24') = (requested_basis IS NOT NULL)),
    CHECK ((state = 'APPROVED') = (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CHECK (state = 'APPROVED' OR (approved_by IS NULL AND approved_at IS NULL))
);

CREATE INDEX election_change_entity ON tenancy.election_change (tenant_id, entity_ref, state);

ALTER TABLE tenancy.election_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.election_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.election_change
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.election_change TO tenancy_runtime;
