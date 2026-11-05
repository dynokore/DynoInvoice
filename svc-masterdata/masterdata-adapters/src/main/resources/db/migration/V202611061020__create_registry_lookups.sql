-- V202611061020__create_registry_lookups.sql (INV-T-4946; INV-REQ-318, INV-REQ-319, INV-REQ-320, INV-REQ-802,
-- INV-REQ-1290; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as masterdata_migrate (never at start-up). The two registry caches hold one
-- answer per Peppol ID and per UEN until a later lookup replaces it; the lookup record is the evidence of one lookup
-- (queried value, response, time) and is never altered; the register suggestion and the network-registration item are
-- the operator-facing state of INV-REQ-1290 and INV-REQ-320.

CREATE TABLE masterdata.directory_cache (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    peppol_id       text        NOT NULL CHECK (length(peppol_id) BETWEEN 1 AND 128),
    found           boolean     NOT NULL,
    ap_reference    text        CHECK (length(ap_reference) BETWEEN 1 AND 64),
    looked_up_at    timestamptz NOT NULL,
    record_key      text        CHECK (length(record_key) BETWEEN 1 AND 64),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, peppol_id)
);

ALTER TABLE masterdata.directory_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.directory_cache FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.directory_cache
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.directory_cache TO masterdata_runtime;

CREATE TABLE masterdata.register_cache (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    uen             text        NOT NULL CHECK (length(uen) BETWEEN 1 AND 64),
    found           boolean     NOT NULL,
    status          text        NOT NULL CHECK (length(status) BETWEEN 1 AND 40),
    gst_number      text        CHECK (length(gst_number) BETWEEN 1 AND 40),
    registered_name text        CHECK (length(registered_name) BETWEEN 1 AND 256),
    as_of           date,
    looked_up_at    timestamptz NOT NULL,
    record_key      text        CHECK (length(record_key) BETWEEN 1 AND 64),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, uen)
);

ALTER TABLE masterdata.register_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.register_cache FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.register_cache
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.register_cache TO masterdata_runtime;

CREATE TABLE masterdata.lookup_record (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    record_key      text        NOT NULL CHECK (length(record_key) BETWEEN 1 AND 64),  -- the `reference` of EvidenceArtefactRecorded
    entity_ref      text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    kind            text        NOT NULL CHECK (kind IN ('DIRECTORY', 'REGISTER')),
    queried         text        NOT NULL CHECK (length(queried) BETWEEN 1 AND 128),
    bytes           text        NOT NULL,                                     -- the stored record: queried value, response, time
    hash            text        NOT NULL CHECK (hash ~ '^[0-9a-f]{64}$'),     -- SHA-256 of `bytes`, as published
    looked_up_at    timestamptz NOT NULL,
    degradation     text        CHECK (degradation ~ '^[A-Z]{2,10}-[0-9]{3}$'),  -- MDM-008 when the directory was unreachable
    correlation_id  text        NOT NULL CHECK (length(correlation_id) BETWEEN 1 AND 128),  -- the submission's link (envelope)
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, record_key)
);

ALTER TABLE masterdata.lookup_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.lookup_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.lookup_record
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.lookup_record TO masterdata_runtime;

CREATE TABLE masterdata.register_suggestion (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    suggestion_ref  text        NOT NULL CHECK (length(suggestion_ref) BETWEEN 1 AND 40),
    entity_ref      text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    party_ref       text        NOT NULL CHECK (length(party_ref) BETWEEN 1 AND 40),
    field           text        NOT NULL CHECK (field IN ('REGISTERED_NAME', 'GSTN')),
    register_value  text        NOT NULL CHECK (length(register_value) BETWEEN 1 AND 256),
    current_value   text        CHECK (length(current_value) BETWEEN 1 AND 256),
    state           text        NOT NULL CHECK (state IN ('PENDING', 'CONFIRMED', 'SUPERSEDED')),
    suggested_at    timestamptz NOT NULL,
    confirmed_at    timestamptz,
    evidence_ref    text        CHECK (length(evidence_ref) BETWEEN 1 AND 64),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, suggestion_ref),
    CHECK ((state = 'CONFIRMED') = (confirmed_at IS NOT NULL))
);

ALTER TABLE masterdata.register_suggestion ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.register_suggestion FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.register_suggestion
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.register_suggestion TO masterdata_runtime;

CREATE INDEX register_suggestion_order ON masterdata.register_suggestion (tenant_id, suggested_at, suggestion_ref);

CREATE TABLE masterdata.network_registration (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    entity_ref      text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    state           text        NOT NULL CHECK (state IN ('OUTSTANDING', 'CONFIRMED')),
    ap_reference    text        CHECK (length(ap_reference) BETWEEN 1 AND 64),
    confirmed_at    timestamptz,
    last_checked_at timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, entity_ref),
    CHECK ((state = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CHECK (state = 'CONFIRMED' OR ap_reference IS NULL)
);

ALTER TABLE masterdata.network_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.network_registration FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.network_registration
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.network_registration TO masterdata_runtime;
