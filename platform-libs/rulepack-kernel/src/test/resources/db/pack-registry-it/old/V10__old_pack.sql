-- A holder's existing registry table from before INV-Q-2964: PackRegistryMigration.ddl(schema = "rulepack_test",
-- table = "old_pack") with the old lineage constraint (plain UNIQUE, so two NULL-tenant rows of one version are
-- admitted) and row-level security left disabled; every line is the generator's output for that table with only
-- those two changes.
CREATE TABLE rulepack_test.old_pack (
    id                 uuid PRIMARY KEY,
    pack_id            text NOT NULL,
    pack_type          text NOT NULL,
    version            text NOT NULL,
    tenant_id          uuid NULL,
    effective_from     date NOT NULL,
    effective_to       date NULL,
    min_engine_version text NOT NULL,
    manifest           jsonb NOT NULL,
    checksums          jsonb NOT NULL,
    signature          bytea NOT NULL,
    contents           jsonb NOT NULL,
    trusted_anchor     text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT old_pack_lineage_key UNIQUE (pack_type, tenant_id, version)
);
CREATE INDEX old_pack_resolution_idx ON rulepack_test.old_pack (pack_type, tenant_id, effective_from);
ALTER TABLE rulepack_test.old_pack DISABLE ROW LEVEL SECURITY;
ALTER TABLE rulepack_test.old_pack FORCE ROW LEVEL SECURITY;
CREATE POLICY old_pack_tenant_isolation ON rulepack_test.old_pack
    USING      ((tenant_id IS NULL AND (nullif(current_setting('app.tenant_id', true), '') IS NOT NULL OR current_setting('app.platform_operator', true) = 'true')) OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK ((tenant_id IS NULL AND current_setting('app.platform_operator', true) = 'true') OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
