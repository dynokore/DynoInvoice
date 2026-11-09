-- INV-T-18775 (INV-REQ-506, INV-REQ-504; CS-19, ARC-20, TPL-12). The replica of RulePackStaged (packType SCOPE) and
-- RulePackActivated: the approved exchange-rate sources of each staged scope pack version and the activations (version
-- and date) of every pack identifier, as the tenancy-svc replica holds them (V202610271210 of svc-tenancy). Run only by
-- the migration Job as masterdata_migrate. A row is inserted once and never updated: a replayed event leaves one row.
CREATE TABLE masterdata.scope_pack_version (
    tenant_id             uuid        NOT NULL,
    pack_identifier       text        NOT NULL CHECK (length(pack_identifier) BETWEEN 1 AND 200),
    pack_version          text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 100),
    exchange_rate_sources text        NOT NULL,
    recorded_at           timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, pack_identifier, pack_version)
);

CREATE TABLE masterdata.scope_pack_activation (
    tenant_id       uuid        NOT NULL,
    pack_identifier text        NOT NULL CHECK (length(pack_identifier) BETWEEN 1 AND 200),
    pack_version    text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 100),
    effective_from  date        NOT NULL,
    recorded_at     timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, pack_identifier, pack_version)
);

CREATE INDEX scope_pack_activation_date ON masterdata.scope_pack_activation (tenant_id, pack_identifier, effective_from DESC);

ALTER TABLE masterdata.scope_pack_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.scope_pack_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.scope_pack_version
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

ALTER TABLE masterdata.scope_pack_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.scope_pack_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.scope_pack_activation
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.scope_pack_version TO masterdata_runtime;
GRANT SELECT, INSERT ON masterdata.scope_pack_activation TO masterdata_runtime;
