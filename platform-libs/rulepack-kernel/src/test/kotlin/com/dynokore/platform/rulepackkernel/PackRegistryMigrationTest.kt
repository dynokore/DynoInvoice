package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The DDL a pack holder includes in its own migration (INV-T-4137; coordinator ruling INV-Q-4117). The behaviour
 * against a live database is proved by [PackRegistryMigrationPostgresIT]; this pins the text a reviewer reads.
 */
class PackRegistryMigrationTest {
    private val ddl =
        PackRegistryMigration.ddl(
            schema = "rules",
            table = "rule_pack_version",
            runtimeRole = "rules_app",
            sysRole = "rules_sys",
        )

    @Test
    fun `tenant_id is NOT NULL and the lineage key is a plain UNIQUE`() {
        assertTrue(ddl.contains("CREATE TABLE rules.rule_pack_version ("))
        assertTrue(ddl.contains("tenant_id          uuid NOT NULL,"))
        assertTrue(ddl.contains("CONSTRAINT rule_pack_version_lineage_key UNIQUE (pack_type, tenant_id, version)"))
        assertTrue(
            ddl.contains(
                "CREATE INDEX rule_pack_version_resolution_idx ON rules.rule_pack_version " +
                    "(pack_type, tenant_id, effective_from)",
            ),
        )
        assertFalse(ddl.contains("NULLS NOT DISTINCT"))
        assertFalse(ddl.contains("IS NULL AND"), "no tenant_id IS NULL path is left")
        assertFalse(ddl.contains("platform_operator"), "the app.platform_operator branch is removed")
    }

    @Test
    fun `row-level security is enabled, forced and keyed on app_tenant_id only`() {
        assertTrue(ddl.contains("ALTER TABLE rules.rule_pack_version ENABLE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("ALTER TABLE rules.rule_pack_version FORCE ROW LEVEL SECURITY;"))
        val platform = "'${TenantId.PLATFORM}'::uuid"
        val bound = "nullif(current_setting('app.tenant_id', true), '')::uuid"
        val own = "(tenant_id = $bound AND tenant_id <> $platform)"
        assertTrue(
            ddl.contains(
                "CREATE POLICY rule_pack_version_tenant_read ON rules.rule_pack_version FOR SELECT\n" +
                    "    USING (tenant_id = $bound OR ($bound IS NOT NULL AND tenant_id = $platform));",
            ),
        )
        assertTrue(ddl.contains("_tenant_insert ON rules.rule_pack_version FOR INSERT WITH CHECK $own;"))
        assertTrue(ddl.contains("FOR UPDATE USING $own WITH CHECK $own;"))
        assertTrue(ddl.contains("FOR DELETE USING $own;"))
        assertTrue(ddl.contains("FOR INSERT TO rules_sys WITH CHECK (tenant_id = $platform);"))
    }

    @Test
    fun `registered_by is app actor at insert and fixed afterwards`() {
        // tenancy-kernel's shared actor rule (INV-Q-4614 (A), INV-Q-4622 (A)); the set itself is pinned there.
        val actor = BlankText.SQL_NOT_ACTOR
        assertTrue(ddl.contains("registered_by      text NOT NULL CHECK (registered_by !~ '$actor'),"))
        assertEquals(2, ddl.split("IF v_actor IS NULL OR v_actor ~ '$actor' THEN").size - 1)
        assertFalse(Regex("""\b(b|l|r)?trim\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(ddl), "a trim check left")
        val blank = BlankText.SQL_BLANK
        listOf("p_pack_id", "p_pack_type", "p_version", "p_min_engine_version", "p_trusted_anchor").forEach {
            assertEquals(1, ddl.split("OR coalesce($it, '') ~ '$blank'").size - 1, it)
        }
        assertTrue(ddl.contains("IF NEW.registered_by IS DISTINCT FROM v_actor THEN"))
        assertTrue(ddl.contains("IF NEW.registered_by IS DISTINCT FROM OLD.registered_by THEN"))
        assertTrue(ddl.contains("BEFORE INSERT ON rules.rule_pack_version"))
        assertTrue(ddl.contains("BEFORE UPDATE ON rules.rule_pack_version"))
        assertTrue(ddl.contains("p_contents, p_trusted_anchor, v_actor);"))
    }

    @Test
    fun `register_platform_pack is a sys-owned definer for the runtime login only`() {
        val signature = "rules.register_platform_pack(${PackRegistryMigration.REGISTER_PLATFORM_PACK_ARGUMENTS})"
        assertTrue(ddl.contains("CREATE FUNCTION rules.register_platform_pack(p_id uuid,"))
        assertTrue(ddl.contains("SECURITY DEFINER SET search_path = pg_catalog, rules, pg_temp AS"))
        assertTrue(ddl.contains("current_setting('app.actor', true)"))
        assertTrue(ddl.contains("ALTER FUNCTION $signature OWNER TO rules_sys;"))
        assertTrue(ddl.contains("REVOKE EXECUTE ON FUNCTION $signature FROM PUBLIC;"))
        assertTrue(ddl.contains("GRANT EXECUTE ON FUNCTION $signature TO rules_app;"))
        assertFalse(Regex("""(?i)\bexecute\s+(format|'|\$|[a-z_]+\s*;)""").containsMatchIn(ddl), "static SQL only")
    }

    @Test
    fun `the reference table name is the schema-qualified table`() {
        assertEquals("rules.rule_pack_version", PackRegistryMigration.referenceTable("rules", "rule_pack_version"))
        assertThrows(IllegalArgumentException::class.java) { PackRegistryMigration.referenceTable("Rules", "t") }
    }

    @Test
    fun `refuses a name that is not a plain identifier, and one role for both`() {
        val bad =
            listOf(
                listOf("rules; DROP TABLE x --", "rule_pack_version", "rules_app", "rules_sys"),
                listOf("rules", "Rule Pack Version", "rules_app", "rules_sys"),
                listOf("rules", "rule_pack_version", "rules app", "rules_sys"),
                listOf("rules", "rule_pack_version", "rules_app", "rules_sys;"),
                listOf("rules", "rule_pack_version", "rules_sys", "rules_sys"),
            )
        bad.forEach { names ->
            assertThrows(IllegalArgumentException::class.java) {
                PackRegistryMigration.ddl(names[0], names[1], runtimeRole = names[2], sysRole = names[3])
            }
        }
    }
}
