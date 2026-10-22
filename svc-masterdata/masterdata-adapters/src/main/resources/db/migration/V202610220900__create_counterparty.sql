-- V202610220900__create_counterparty.sql (INV-T-4943; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as masterdata_migrate (never at start-up). A counterparty is a head row and
-- an append-only list of versions (INV-REQ-2576): every write inserts the next version with the whole record as it then
-- stood, so a version recorded earlier is never altered and the highest version is current. The head row serialises
-- concurrent writers on one counterparty without refusing any: each gets the next number (no lost-update protection).
-- Every identifier and every approved scheme status item carries its provenance (provided_by, evidence_ref,
-- captured_at: INV-REQ-314), and `MIGRATED` is set only by an applied import (INV-REQ-1111). The runtime login never
-- updates a version row and never deletes.

CREATE TABLE masterdata.counterparty (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    party_ref       text        NOT NULL CHECK (length(party_ref) BETWEEN 1 AND 40),
    last_version    bigint      NOT NULL CHECK (last_version >= 1),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, party_ref)
);

ALTER TABLE masterdata.counterparty ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.counterparty FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.counterparty
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.counterparty TO masterdata_runtime;

CREATE TABLE masterdata.counterparty_version (
    id               uuid        PRIMARY KEY,                                 -- UUIDv7, assigned by the application
    tenant_id        uuid        NOT NULL,                                    -- FR-G.5
    party_ref        text        NOT NULL CHECK (length(party_ref) BETWEEN 1 AND 40),
    party_version    bigint      NOT NULL CHECK (party_version >= 1),
    recorded_at      timestamptz NOT NULL,
    legal_name       text        CHECK (length(legal_name) BETWEEN 1 AND 200),
    address_line1    text        CHECK (length(address_line1) BETWEEN 1 AND 200),
    address_line2    text        CHECK (length(address_line2) BETWEEN 1 AND 200),
    postal_code      text        CHECK (length(postal_code) BETWEEN 1 AND 20),
    provided_by      text        NOT NULL CHECK (provided_by IN ('INTERNAL', 'COUNTERPARTY', 'REGISTER', 'MIGRATED')),
    arcs_agreed      boolean,                                                 -- the ARCS written-agreement default (INV-REQ-2434)
    arcs_provided_by text        CHECK (arcs_provided_by IN ('INTERNAL', 'COUNTERPARTY', 'REGISTER', 'MIGRATED')),
    arcs_evidence_ref text       CHECK (length(arcs_evidence_ref) BETWEEN 1 AND 256),
    arcs_captured_at timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, party_ref, party_version),
    CHECK ((arcs_agreed IS NULL) = (arcs_provided_by IS NULL)
        AND (arcs_agreed IS NULL) = (arcs_evidence_ref IS NULL)
        AND (arcs_agreed IS NULL) = (arcs_captured_at IS NULL))               -- the default has its provenance, whole
);

ALTER TABLE masterdata.counterparty_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.counterparty_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.counterparty_version
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.counterparty_version TO masterdata_runtime;

CREATE TABLE masterdata.counterparty_identifier (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    party_ref       text        NOT NULL CHECK (length(party_ref) BETWEEN 1 AND 40),
    party_version   bigint      NOT NULL CHECK (party_version >= 1),
    scheme          text        NOT NULL CHECK (scheme IN ('UEN', 'GSTN', 'PEPPOL_PARTICIPANT_ID', 'OVERSEAS_REGISTRATION', 'NONE')),
    identifier      text        NOT NULL CHECK (length(identifier) BETWEEN 1 AND 64),
    provided_by     text        NOT NULL CHECK (provided_by IN ('INTERNAL', 'COUNTERPARTY', 'REGISTER', 'MIGRATED')),
    evidence_ref    text        NOT NULL CHECK (length(evidence_ref) BETWEEN 1 AND 256),
    captured_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, party_ref, party_version, scheme)
);
CREATE INDEX counterparty_identifier_lookup ON masterdata.counterparty_identifier (tenant_id, scheme, identifier);

ALTER TABLE masterdata.counterparty_identifier ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.counterparty_identifier FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.counterparty_identifier
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.counterparty_identifier TO masterdata_runtime;

CREATE TABLE masterdata.counterparty_scheme_status (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    party_ref       text        NOT NULL CHECK (length(party_ref) BETWEEN 1 AND 40),
    party_version   bigint      NOT NULL CHECK (party_version >= 1),
    ordinal         integer     NOT NULL CHECK (ordinal >= 0),
    scheme          text        NOT NULL CHECK (length(scheme) BETWEEN 1 AND 20),
    effective_from  date        NOT NULL,
    effective_to    date,
    provided_by     text        NOT NULL CHECK (provided_by IN ('INTERNAL', 'COUNTERPARTY', 'REGISTER', 'MIGRATED')),
    evidence_ref    text        NOT NULL CHECK (length(evidence_ref) BETWEEN 1 AND 256),
    captured_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, party_ref, party_version, ordinal),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

ALTER TABLE masterdata.counterparty_scheme_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.counterparty_scheme_status FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.counterparty_scheme_status
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.counterparty_scheme_status TO masterdata_runtime;

-- The organisation structure as this service holds it (INV-Q-8427 A): one row per node, written from the `node` of the
-- events EntityProfileChanged and OrganisationStructureChanged; a state with a structure version at or below the held one
-- is ignored, a newer one replaces the node whole. It answers sameTenant and sameGroup without a synchronous call.
CREATE TABLE masterdata.structure_node (
    id                uuid        PRIMARY KEY,                                -- UUIDv7, assigned by the application
    tenant_id         uuid        NOT NULL,                                   -- FR-G.5
    node_ref          text        NOT NULL CHECK (length(node_ref) BETWEEN 1 AND 64),
    kind              text        NOT NULL CHECK (length(kind) BETWEEN 1 AND 40),
    parent_ref        text        CHECK (length(parent_ref) BETWEEN 1 AND 64),
    retired           boolean     NOT NULL,
    uen               text        CHECK (length(uen) BETWEEN 1 AND 64),
    gstn              text        CHECK (length(gstn) BETWEEN 1 AND 20),
    structure_version bigint      NOT NULL CHECK (structure_version >= 0),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, node_ref)
);

ALTER TABLE masterdata.structure_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.structure_node FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.structure_node
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.structure_node TO masterdata_runtime;
