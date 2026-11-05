-- V202611031200__create_mapping_acknowledgement_and_installation_fact.sql (INV-T-4936; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- The client's acknowledgement of a mapping pack version (INV-REQ-996), read by getMappingAcknowledgement; the row is
-- written by acknowledgeMapping. One row per tenant, pack version and client entity.
CREATE TABLE access.mapping_acknowledgement (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    pack_version     text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 16),
    client_ref       text        NOT NULL CHECK (length(client_ref) BETWEEN 1 AND 64),
    acknowledged_by  text        NOT NULL CHECK (length(acknowledged_by) BETWEEN 1 AND 255),  -- the acting user ID, never a role
    acknowledged_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, pack_version, client_ref)
);

-- Whether the installation has a tax.approver distinct from the person who runs the server (INV-REQ-992). It is
-- platform data, not tenant data, so the one row carries the reserved platform tenant (tenancy-kernel TenantId.PLATFORM)
-- and is read and written under that binding, like the verification keys; the column is still NOT NULL under forced RLS.
CREATE TABLE access.installation_approver_fact (
    tenant_id             uuid        NOT NULL PRIMARY KEY,
    distinct_tax_approver boolean     NOT NULL,
    recorded_at           timestamptz NOT NULL,
    recorded_by           text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 255)  -- the acting user ID, never a role
);

-- The Idempotency-Key journal of recordInstallationApproverFact (INV-Q-15370): the body and the response stored under the
-- key, which a later request with the same key and a changed fact replaces. Platform data, like the fact itself.
CREATE TABLE access.installation_fact_request (
    tenant_id             uuid        NOT NULL,
    actor                 text        NOT NULL CHECK (length(actor) BETWEEN 1 AND 255),  -- the acting user ID, never a role
    idempotency_key       uuid        NOT NULL,
    distinct_tax_approver boolean     NOT NULL,  -- the body of the request
    recorded_at           timestamptz NOT NULL,  -- the response stored under the key
    PRIMARY KEY (tenant_id, actor, idempotency_key)
);

ALTER TABLE access.mapping_acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.mapping_acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.mapping_acknowledgement
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.installation_approver_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.installation_approver_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.installation_approver_fact
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.installation_fact_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.installation_fact_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.installation_fact_request
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT ON access.mapping_acknowledgement TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.installation_approver_fact TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.installation_fact_request TO access_runtime;
