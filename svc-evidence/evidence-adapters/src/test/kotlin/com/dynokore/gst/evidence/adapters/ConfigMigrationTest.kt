package com.dynokore.gst.evidence.adapters

import com.dynokore.platform.configresolver.ConfigSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * INV-T-6540 (TPL-12, INV-Q-12592 A): V10 is the output of config-resolver's `ConfigSchema.ddl` of the pinned
 * platform-libs version (the settings tables and `config_option_value_maximum`) after its header comment. A change of
 * the generator, or an edit by hand, fails here. A database migrated through all versions holds each object once.
 */
class ConfigMigrationTest {
    private val file = "db/migration/V10__evidence_config.sql"

    @Test
    fun `V10 holds exactly the config-resolver DDL of the pinned version`() {
        val text = checkNotNull(javaClass.classLoader.getResource(file)) { "$file is missing" }.readText()
        val body = text.lines().dropWhile { it.startsWith("--") }.joinToString("\n")
        body.trim() shouldBe ConfigSchema.ddl(EvidenceDb.SCHEMA, EvidenceDb.RUNTIME, EvidenceDb.SYS).trim()
    }

    @Test
    fun `V10 creates the function config_option_value_maximum and no idempotency object`() {
        val text = checkNotNull(javaClass.classLoader.getResource(file)).readText()
        text.contains("function evidence.${ConfigSchema.OPTION_VALUE_MAXIMUM}") shouldBe true
        text.contains("idempotency_key") shouldBe false
    }

    @Test
    fun `a database migrated through all versions holds each config and idempotency object exactly once`() {
        val tables =
            listOf(
                "config_setting",
                "config_installation_setting",
                "config_resolved_value",
                "config_relaxation_acknowledgement",
                "idempotency_key",
            )
        EvidenceDb.superuser().use { connection ->
            tables.forEach { table ->
                count(
                    connection,
                    "select count(*) from pg_tables where schemaname = 'evidence' and tablename = '$table'",
                ) shouldBe
                    1
            }
            count(
                connection,
                "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                    "where n.nspname = 'evidence' and p.proname = '${ConfigSchema.OPTION_VALUE_MAXIMUM}'",
            ) shouldBe 1
        }
    }

    private fun count(
        connection: java.sql.Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { s ->
            s.executeQuery(sql).use {
                it.next()
                it.getInt(1)
            }
        }
}
