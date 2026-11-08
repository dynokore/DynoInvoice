-- V202610281020: the operating profile and version applied to a scope (INV-REQ-1245 b2, INV-Q-5022 A). Only the scope,
-- an opaque profile reference and the version are kept; the reference is not checked against the catalogue. Two tables,
-- because a per-entity table keeps a non-nullable entity reference and no key of {tenant_id} alone (INV-AC-2041): one
-- row per entity for scope ENTITY, and for scope TENANT one row per tenant in applied_profile_tenant, keyed by tenant_id
-- alone (the scope TENANT is the table; owner answer INV-Q-9833 A exempts exactly that table name from the two
-- per-entity rules of the boundary test). A new record of a scope replaces that scope's row. Run by tenancy_migrate. Forced RLS on the signed binding (INV-REQ-854 b1).
SET lock_timeout = '5s';

CREATE TABLE tenancy.applied_profile_entity (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    entity_id       uuid        NOT NULL,
    profile_ref     text        NOT NULL CHECK (length(profile_ref) BETWEEN 1 AND 128),
    profile_version bigint      NOT NULL CHECK (profile_version >= 1),
    recorded_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, entity_id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

CREATE TABLE tenancy.applied_profile_tenant (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    profile_ref     text        NOT NULL CHECK (length(profile_ref) BETWEEN 1 AND 128),
    profile_version bigint      NOT NULL CHECK (profile_version >= 1),
    recorded_at     timestamptz NOT NULL,
    UNIQUE (tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (tenant_id)
);

ALTER TABLE tenancy.applied_profile_entity ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.applied_profile_entity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.applied_profile_entity
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.applied_profile_tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.applied_profile_tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.applied_profile_tenant
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.applied_profile_entity TO tenancy_runtime;
GRANT SELECT, INSERT, UPDATE ON tenancy.applied_profile_tenant TO tenancy_runtime;
