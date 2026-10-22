-- V202610221000__create_commercial.sql (TPL-12, CS-19, INV-REQ-906 b1)
-- The commercial records of commercial.admin: the stored versions of a tenant's plan with its advisory entitlements
-- (INV-ADR-272) and the qualification outcome of an installation. Both are PLATFORM data (the operator's, not a
-- tenant's), so neither has a tenant column and each is declared platform-scoped by its table comment. The runtime
-- login reads and inserts only: a stored version and a recorded outcome are never changed or removed.

CREATE TABLE controlplane.plan_version (
    tenant_ref                text        NOT NULL CHECK (length(tenant_ref) BETWEEN 1 AND 64),
    plan_version              text        NOT NULL CHECK (length(plan_version) BETWEEN 1 AND 16),
    max_entities              integer     NOT NULL CHECK (max_entities >= 0),
    max_documents_per_month   integer     NOT NULL CHECK (max_documents_per_month >= 0),
    recorded_by               text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 128),
    recorded_at               timestamptz NOT NULL,
    PRIMARY KEY (tenant_ref, plan_version)
);
COMMENT ON TABLE controlplane.plan_version IS 'platform-scoped: no tenant data';

CREATE TABLE controlplane.qualification (
    customer_ref     text        NOT NULL CHECK (length(customer_ref) BETWEEN 1 AND 64),
    installation_ref text        NOT NULL CHECK (length(installation_ref) BETWEEN 1 AND 64),
    outcome          text        NOT NULL CHECK (outcome IN ('QUALIFIED', 'NOT_QUALIFIED')),
    recorded_by      text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 128),
    recorded_at      timestamptz NOT NULL,
    PRIMARY KEY (customer_ref, installation_ref)
);
COMMENT ON TABLE controlplane.qualification IS 'platform-scoped: no tenant data';

GRANT SELECT, INSERT ON controlplane.plan_version TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.qualification TO controlplane_runtime;
