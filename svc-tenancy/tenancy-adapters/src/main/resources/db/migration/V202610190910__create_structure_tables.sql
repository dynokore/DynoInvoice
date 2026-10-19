-- V202610190910: the four organisation levels, the short-code register and the structure-version sequence
-- (INV-REQ-850, INV-REQ-652, INV-REQ-1356, INV-REQ-852, INV-REQ-644; CS-19, TPL-12). Run by tenancy_migrate.
-- Every table carries tenant_id NOT NULL with forced RLS on the signed binding (INV-REQ-854 b1).

-- One sequence for every structure node state, in the changing transaction (INV-API-3327).
SET lock_timeout = '5s';

CREATE SEQUENCE tenancy.structure_version_seq AS bigint START WITH 1 INCREMENT BY 1 MINVALUE 1 NO CYCLE;

CREATE TABLE tenancy.tenant (
    tenant_id          uuid        PRIMARY KEY,
    ref                text        NOT NULL CHECK (length(ref) BETWEEN 1 AND 64),
    legal_name         text        NOT NULL CHECK (length(legal_name) BETWEEN 1 AND 200),
    plan_ref           text        NOT NULL CHECK (length(plan_ref) BETWEEN 1 AND 64),
    contract_reference text        NOT NULL CHECK (length(contract_reference) BETWEEN 1 AND 64),
    retired            boolean     NOT NULL DEFAULT false,
    structure_version  bigint      NOT NULL CHECK (structure_version >= 1),
    created_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 1,
    UNIQUE (ref),
    UNIQUE (contract_reference)
);

CREATE TABLE tenancy.tenant_group (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    ref               text        NOT NULL CHECK (length(ref) BETWEEN 1 AND 64),
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    kind              text        NOT NULL CHECK (kind IN ('GST_GROUP', 'DIVISIONAL')),
    gstn              text        CHECK (gstn IS NULL OR length(gstn) BETWEEN 1 AND 20),
    retired           boolean     NOT NULL DEFAULT false,
    structure_version bigint      NOT NULL CHECK (structure_version >= 1),
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 1,
    UNIQUE (ref),
    UNIQUE (tenant_id, id)
);

-- One GST registration. A GST-group member carries its own UEN and sits under the group's GSTN; a division carries the
-- parent UEN and its own division GSTN in gstn (INV-AC-346, INV-AC-347, INV-AC-1814).
CREATE TABLE tenancy.registered_entity (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    ref               text        NOT NULL CHECK (length(ref) BETWEEN 1 AND 64),
    group_id          uuid        NOT NULL,
    legal_name        text        NOT NULL CHECK (length(legal_name) BETWEEN 1 AND 200),
    constitution      text        NOT NULL,
    uen               text        CHECK (uen IS NULL OR length(uen) BETWEEN 1 AND 20),
    gstn              text        CHECK (gstn IS NULL OR length(gstn) BETWEEN 1 AND 20),
    code              text        NOT NULL CHECK (code ~ '^[A-Z0-9]{1,10}$'),
    lifecycle_state   text        NOT NULL CHECK (lifecycle_state IN ('DRAFT', 'LIVE', 'SUSPENDED', 'DEREGISTERED', 'RETIRED')),
    go_live_date      date,
    retired           boolean     NOT NULL DEFAULT false,
    structure_version bigint      NOT NULL CHECK (structure_version >= 1),
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 1,
    UNIQUE (ref),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, group_id) REFERENCES tenancy.tenant_group (tenant_id, id)
);

-- Per-entity record: the registered-entity reference is mandatory and enforced here (INV-REQ-644, INV-AC-1714).
CREATE TABLE tenancy.business_unit (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    ref               text        NOT NULL CHECK (length(ref) BETWEEN 1 AND 64),
    entity_id         uuid        NOT NULL,
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    code              text        NOT NULL CHECK (code ~ '^[A-Z0-9]{1,10}$'),
    retired           boolean     NOT NULL DEFAULT false,
    structure_version bigint      NOT NULL CHECK (structure_version >= 1),
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 1,
    UNIQUE (ref),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.registered_entity (tenant_id, id)
);

-- One code namespace per tenant across entities and business units (INV-REQ-850 b7): the primary key refuses a duplicate
-- whichever level holds it. A code used in an aggregate number cannot change (INV-REQ-436); the flag is set by the
-- application when aggregation-svc reports use.
CREATE TABLE tenancy.short_code (
    tenant_id uuid    NOT NULL REFERENCES tenancy.tenant (tenant_id),
    code      text    NOT NULL CHECK (code ~ '^[A-Z0-9]{1,10}$'),
    node_kind text    NOT NULL CHECK (node_kind IN ('ENTITY', 'BUSINESS_UNIT')),
    node_id   uuid    NOT NULL,
    PRIMARY KEY (tenant_id, code),
    UNIQUE (tenant_id, node_id)
);

CREATE INDEX registered_entity_group ON tenancy.registered_entity (tenant_id, group_id);
CREATE INDEX business_unit_entity ON tenancy.business_unit (tenant_id, entity_id);

ALTER TABLE tenancy.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.tenant
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.tenant_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.tenant_group FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.tenant_group
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.registered_entity ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.registered_entity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.registered_entity
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.business_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.business_unit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.business_unit
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.short_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.short_code FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.short_code
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.tenant, tenancy.tenant_group, tenancy.registered_entity, tenancy.business_unit TO tenancy_runtime;
GRANT SELECT, INSERT ON tenancy.short_code TO tenancy_runtime;
GRANT USAGE ON SEQUENCE tenancy.structure_version_seq TO tenancy_runtime;
