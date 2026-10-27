-- V202610271100__create_provisioning.sql (TPL-12, CS-19, INV-REQ-1306, INV-REQ-1386, INV-REQ-906 b1)
-- The region list with the sub-processor statement of each region, the recorded tenant provisioning requests and the
-- issued licences. All four are PLATFORM data (the operator's, not a tenant's): no tenant column, each declared
-- platform-scoped by its table comment. The runtime login reads the region list and cannot change it (INV-Q-8685 A);
-- it inserts requests and licences, and updates only the status of a request, so the region of a recorded request
-- cannot be changed through any statement the service can run (INV-REQ-1306 b2).

CREATE TABLE controlplane.region (
    region_key   text PRIMARY KEY CHECK (length(region_key) BETWEEN 1 AND 64),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200)
);
COMMENT ON TABLE controlplane.region IS 'platform-scoped: no tenant data';

CREATE TABLE controlplane.region_statement (
    region_key text PRIMARY KEY REFERENCES controlplane.region (region_key),
    statement  text NOT NULL CHECK (length(statement) BETWEEN 1 AND 2000)
);
COMMENT ON TABLE controlplane.region_statement IS 'platform-scoped: no tenant data';

INSERT INTO controlplane.region (region_key, display_name) VALUES ('SG', 'Singapore');
INSERT INTO controlplane.region_statement (region_key, statement)
VALUES ('SG', 'Tenant data is stored and processed in Singapore.');

CREATE TABLE controlplane.tenant_provisioning_request (
    contract_reference      text        PRIMARY KEY CHECK (length(contract_reference) BETWEEN 1 AND 64),
    tenant_ref              text        NOT NULL UNIQUE CHECK (length(tenant_ref) BETWEEN 1 AND 64),
    legal_name              text        NOT NULL CHECK (length(legal_name) BETWEEN 1 AND 200),
    plan_ref                text        NOT NULL CHECK (length(plan_ref) BETWEEN 1 AND 64),
    first_administrator_email text      NOT NULL CHECK (length(first_administrator_email) BETWEEN 1 AND 254),
    data_region             text        NOT NULL REFERENCES controlplane.region (region_key),
    status                  text        NOT NULL CHECK (status IN ('PENDING', 'CREATED')),
    invitation_ref          text        NOT NULL,
    invitation_expires_at   timestamptz NOT NULL,
    idempotency_key         uuid        NOT NULL,
    fingerprint             text        NOT NULL,
    recorded_by             text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 128),
    recorded_at             timestamptz NOT NULL,
    UNIQUE (recorded_by, idempotency_key)
);
COMMENT ON TABLE controlplane.tenant_provisioning_request IS 'platform-scoped: no tenant data';

CREATE TABLE controlplane.licence (
    licence_ref      text        PRIMARY KEY CHECK (length(licence_ref) BETWEEN 1 AND 64),
    installation_ref text        NOT NULL CHECK (length(installation_ref) BETWEEN 1 AND 64),
    term             text        NOT NULL CHECK (length(term) BETWEEN 1 AND 32),
    issued_at        timestamptz NOT NULL,
    signed_file      bytea       NOT NULL,
    issued_by        text        NOT NULL CHECK (length(issued_by) BETWEEN 1 AND 128),
    UNIQUE (installation_ref, term)
);
COMMENT ON TABLE controlplane.licence IS 'platform-scoped: no tenant data';

GRANT SELECT ON controlplane.region TO controlplane_runtime;
GRANT SELECT ON controlplane.region_statement TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.tenant_provisioning_request TO controlplane_runtime;
GRANT UPDATE (status) ON controlplane.tenant_provisioning_request TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.licence TO controlplane_runtime;
