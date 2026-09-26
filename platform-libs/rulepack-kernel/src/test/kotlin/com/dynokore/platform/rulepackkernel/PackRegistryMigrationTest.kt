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
