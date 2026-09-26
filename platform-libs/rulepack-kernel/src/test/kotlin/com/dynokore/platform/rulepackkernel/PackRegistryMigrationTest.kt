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
        assertTrue(ddl.contains("CONSTRAINT rule_pack_version_lineage_key UNIQUE (pack_type, tenant_id, version)"))
        assertTrue(
            ddl.contains(
                "CREATE INDEX rule_pack_version_resolution_idx ON rules.rule_pack_version " +
                    "(pack_type, tenant_id, effective_from)",
            ),
        )
    }

    @Test
    fun `enables and forces row-level security with asymmetric read and write predicates`() {
        val ddl = PackRegistryMigration.ddl(schema = "rules", table = "rule_pack_version")

        assertTrue(ddl.contains("ALTER TABLE rules.rule_pack_version ENABLE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("ALTER TABLE rules.rule_pack_version FORCE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("CREATE POLICY rule_pack_version_tenant_isolation ON rules.rule_pack_version"))
        // Read side: both platform-wide (tenant_id IS NULL, e.g. Scope, MDE) and
        // tenant-scoped (e.g. a tenant's own Mapping pack, packs.yaml
        // tenant_overridable: Yes) rows must be visible, and a tenant-scoped row
        // must still fail closed on read (no row matches when app.tenant_id is
        // unset or blank) — CS-19. Real behaviour against a live database is
        // proved by PackRegistryMigrationPostgresIT, not string matching here.
        val boundTenant = "nullif(current_setting('app.tenant_id', true), '')"
        assertTrue(ddl.contains("USING      (tenant_id IS NULL OR tenant_id = $boundTenant::uuid)"))
        // Write side must NOT be the same text as the read side: a tenant-bound
        // session must never be able to insert a tenant_id IS NULL row (only the
        // platform/maintenance identity, i.e. no tenant bound at all, may).
        assertTrue(
            ddl.contains(
                "WITH CHECK ((tenant_id IS NULL AND $boundTenant IS NULL) OR tenant_id = $boundTenant::uuid)",
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
}
