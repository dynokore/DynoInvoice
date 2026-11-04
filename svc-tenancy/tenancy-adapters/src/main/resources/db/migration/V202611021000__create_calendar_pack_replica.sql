-- V202611021000: the replica of RulePackStaged (packType calendar) and RulePackActivated for the calendar packs
-- (INV-T-4930, INV-Q-9725 A). The staged payload is kept whole, so the decoder of INV-T-5055 reads it when a pack is
-- resolved; the activations (version and date) feed PackRegistry.resolve. Forced RLS on the signed binding.
SET lock_timeout = '5s';

CREATE TABLE tenancy.calendar_pack_version (
    tenant_id          uuid        NOT NULL,
    pack_identifier    text        NOT NULL CHECK (length(pack_identifier) BETWEEN 1 AND 200),
    pack_version       text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 100),
    min_engine_version text        NOT NULL CHECK (length(min_engine_version) BETWEEN 1 AND 100),
    payload            text        NOT NULL,
    recorded_at        timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, pack_identifier, pack_version)
);

CREATE TABLE tenancy.calendar_pack_activation (
    tenant_id       uuid        NOT NULL,
    pack_identifier text        NOT NULL CHECK (length(pack_identifier) BETWEEN 1 AND 200),
    pack_version    text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 100),
    effective_from  date        NOT NULL,
    recorded_at     timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, pack_identifier, pack_version)
);

CREATE INDEX calendar_pack_activation_date ON tenancy.calendar_pack_activation (tenant_id, pack_identifier, effective_from DESC);

ALTER TABLE tenancy.calendar_pack_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.calendar_pack_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.calendar_pack_version
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.calendar_pack_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.calendar_pack_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.calendar_pack_activation
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.calendar_pack_version TO tenancy_runtime;
GRANT SELECT, INSERT ON tenancy.calendar_pack_activation TO tenancy_runtime;
