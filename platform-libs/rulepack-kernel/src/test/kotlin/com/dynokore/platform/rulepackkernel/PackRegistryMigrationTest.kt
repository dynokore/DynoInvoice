package com.dynokore.platform.rulepackkernel

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `new_table` done-when: the DDL a pack holder includes in its own migration. */
class PackRegistryMigrationTest {
    @Test
    fun `emits a table with the expected columns, uniqueness constraint and resolution index`() {
        val ddl = PackRegistryMigration.ddl(schema = "rules", table = "rule_pack_version")

        assertTrue(ddl.contains("CREATE TABLE rules.rule_pack_version ("))
        assertTrue(ddl.contains("tenant_id          uuid NULL"))
        assertTrue(ddl.contains("effective_from     date NOT NULL"))
        assertTrue(ddl.contains("effective_to       date NULL"))
        assertTrue(ddl.contains("manifest           jsonb NOT NULL"))
        assertTrue(ddl.contains("signature          bytea NOT NULL"))
        assertTrue(ddl.contains("CONSTRAINT rule_pack_version_lineage_key UNIQUE NULLS NOT DISTINCT (pack_type, tenant_id, version)"))
        assertTrue(
            ddl.contains(
                "CREATE INDEX rule_pack_version_resolution_idx ON rules.rule_pack_version " +
                    "(pack_type, tenant_id, effective_from)",
            ),
        )
    }

    @Test
    fun `enables and forces row-level security with an explicit platform-operator identity`() {
        val ddl = PackRegistryMigration.ddl(schema = "rules", table = "rule_pack_version")

        assertTrue(ddl.contains("ALTER TABLE rules.rule_pack_version ENABLE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("ALTER TABLE rules.rule_pack_version FORCE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("CREATE POLICY rule_pack_version_tenant_isolation ON rules.rule_pack_version"))
        // Read side: both platform-wide (tenant_id IS NULL, e.g. Scope, MDE) and
        // tenant-scoped (e.g. a tenant's own Mapping pack, packs.yaml
        // tenant_overridable: Yes) rows must be visible to a session with *some*
        // identity bound (a tenant, or the platform operator, INV-REQ-1343) —
        // fail-closed for a session with neither (no row matches at all), not just
        // for a tenant-scoped row. Real behaviour against a live database is
        // proved by PackRegistryMigrationPostgresIT, not string matching here.
        val boundTenant = "nullif(current_setting('app.tenant_id', true), '')"
        val isPlatformOperator = "current_setting('app.platform_operator', true) = 'true'"
        val hasSomeIdentity = "($boundTenant IS NOT NULL OR $isPlatformOperator)"
        assertTrue(
            ddl.contains(
                "USING      ((tenant_id IS NULL AND $hasSomeIdentity) OR tenant_id = $boundTenant::uuid)",
            ),
        )
        // Write side must NOT be the same text as the read side: a tenant-bound
        // session must never be able to insert a tenant_id IS NULL row, and "no
        // tenant bound" is not by itself the platform operator (the review
        // escalation this fixes) — only an explicit app.platform_operator = 'true'
        // may insert one.
        assertTrue(
            ddl.contains(
                "WITH CHECK ((tenant_id IS NULL AND $isPlatformOperator) OR tenant_id = $boundTenant::uuid)",
            ),
        )
    }

    @Test
    fun `refuses a schema name that is not a plain identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackRegistryMigration.ddl(schema = "rules; DROP TABLE x --", table = "rule_pack_version")
        }
    }

    @Test
    fun `refuses a table name that is not a plain identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackRegistryMigration.ddl(schema = "rules", table = "Rule Pack Version")
        }
    }

    @Test
    fun `upgrade replaces the lineage constraint with NULLS NOT DISTINCT`() {
        val ddl = PackRegistryMigration.lineageKeyUpgradeDdl(schema = "rules", table = "rule_pack_version")

        assertTrue(ddl.contains("DROP CONSTRAINT rule_pack_version_lineage_key;"))
        assertTrue(ddl.contains("UNIQUE NULLS NOT DISTINCT (pack_type, tenant_id, version)"))
    }
}
