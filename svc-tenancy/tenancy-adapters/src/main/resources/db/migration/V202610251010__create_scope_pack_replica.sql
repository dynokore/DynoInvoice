-- V202610251010: the replica of RulePackStaged (packType scope) and RulePackActivated (INV-REQ-505, INV-REQ-504; ruling
-- INV-Q-9698). The approved exchange-rate sources of each staged scope pack version, and the activations (version and
-- date) of every pack identifier; the VP-24 check reads the version active on the election's effective date.
-- Forced RLS on the signed binding.
SET lock_timeout = '5s';

CREATE TABLE tenancy.scope_pack_version (
    tenant_id              uuid        NOT NULL,
    pack_identifier        text        NOT NULL CHECK (length(pack_identifier) BETWEEN 1 AND 200),
    pack_version           text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 100),
    exchange_rate_sources  text        NOT NULL,
    recorded_at            timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, pack_identifier, pack_version)
);

CREATE TABLE tenancy.scope_pack_activation (
    tenant_id       uuid        NOT NULL,
    pack_identifier text        NOT NULL CHECK (length(pack_identifier) BETWEEN 1 AND 200),
    pack_version    text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 100),
    effective_from  date        NOT NULL,
    recorded_at     timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, pack_identifier, pack_version)
);

CREATE INDEX scope_pack_activation_date ON tenancy.scope_pack_activation (tenant_id, pack_identifier, effective_from DESC);

ALTER TABLE tenancy.scope_pack_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.scope_pack_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.scope_pack_version
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.scope_pack_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.scope_pack_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.scope_pack_activation
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.scope_pack_version TO tenancy_runtime;
GRANT SELECT, INSERT ON tenancy.scope_pack_activation TO tenancy_runtime;
