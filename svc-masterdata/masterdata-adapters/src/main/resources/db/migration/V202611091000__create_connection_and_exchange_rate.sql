-- INV-T-18774 (INV-REQ-506, INV-REQ-834, INV-REQ-835; INV-Q-10027; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as masterdata_migrate (never at start-up). The connection record of one
-- external service and environment (endpoint, secret-store reference, environment tag, owner service; never the secret)
-- and the effective-dated exchange rate records. A rate record is never updated: a repeat of (source, pair, date) is a
-- new row with the next revision, and the unique key makes two racing writers of one revision a conflict, not an overwrite.

CREATE SEQUENCE masterdata.connection_ref_seq AS bigint START WITH 1 MINVALUE 1 NO CYCLE;

CREATE TABLE masterdata.connection (
    id               uuid        PRIMARY KEY,                                   -- UUIDv7, assigned by the application
    tenant_id        uuid        NOT NULL,                                      -- FR-G.5
    record_ref       text        NOT NULL CHECK (length(record_ref) BETWEEN 1 AND 40),
    external_service text        NOT NULL CHECK (length(external_service) BETWEEN 1 AND 40),
    endpoint         text        NOT NULL CHECK (length(endpoint) BETWEEN 1 AND 512),
    credential_ref   text        NOT NULL CHECK (length(credential_ref) BETWEEN 1 AND 100), -- the secret-store key, never the secret
    environment_tag  text        NOT NULL CHECK (environment_tag IN ('PRODUCTION', 'SANDBOX')),
    owner_service    text        NOT NULL CHECK (length(owner_service) BETWEEN 1 AND 40),
    created_at       timestamptz NOT NULL,
    UNIQUE (tenant_id, record_ref),
    UNIQUE (tenant_id, external_service, environment_tag)                       -- one record per service and environment
);

ALTER TABLE masterdata.connection ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.connection FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.connection
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.connection TO masterdata_runtime;
GRANT USAGE ON SEQUENCE masterdata.connection_ref_seq TO masterdata_runtime;

CREATE TABLE masterdata.exchange_rate (
    id             uuid           PRIMARY KEY,
    tenant_id      uuid           NOT NULL,
    source         text           NOT NULL CHECK (length(source) BETWEEN 1 AND 40),
    base_currency  text           NOT NULL CHECK (base_currency ~ '^[A-Z]{3}$'),
    quote_currency text           NOT NULL CHECK (quote_currency ~ '^[A-Z]{3}$'),
    rate_date      date           NOT NULL,
    rate           numeric(25, 8) NOT NULL CHECK (rate > 0),
    provenance     text           NOT NULL CHECK (provenance IN ('MANUAL_ENTRY', 'IMPORT')),
    evidence_ref   text           CHECK (length(evidence_ref) BETWEEN 1 AND 64),
    import_id      text           CHECK (length(import_id) BETWEEN 1 AND 40),
    revision       integer        NOT NULL CHECK (revision >= 1),
    loaded_at      timestamptz    NOT NULL,
    CHECK ((provenance = 'MANUAL_ENTRY') = (evidence_ref IS NOT NULL AND import_id IS NULL)
        AND (provenance = 'IMPORT') = (import_id IS NOT NULL AND evidence_ref IS NULL)),
    CONSTRAINT exchange_rate_key UNIQUE (tenant_id, source, base_currency, quote_currency, rate_date, revision)
);

ALTER TABLE masterdata.exchange_rate ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.exchange_rate FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.exchange_rate
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.exchange_rate TO masterdata_runtime;           -- no UPDATE: a stored rate is never changed
