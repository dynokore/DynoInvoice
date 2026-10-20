package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

private class RegistryPostgres(
    image: DockerImageName,
) : PostgreSQLContainer<RegistryPostgres>(image)

/**
 * The shape of the registry table after the rename of `version` to `pack_version` (INV-Q-7438, INV-T-7524) on a real
 * PostgreSQL 16, 17 and 18: its columns, its lineage key and resolution index, the platform write path and the
 * code-generation predicate of gst.jooq-codegen.
 */
@Tag("integration")
class PackRegistryColumnsPostgresIT {
    @ParameterizedTest
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the registry table carries pack_version and gst jooq-codegen finds no version column`(image: String) {
        val container = RegistryPostgres(DockerImageName.parse(image)).withPackRegistryInit()
        container.start()
        try {
            assertTheColumnsAreTheRenamedSet(container)
            assertTheLineageKeyDefinitionAndTheResolutionIndex(container)
            val app = TenancyProbe.connect(container.jdbcUrl, "rulepack_kernel_app", "rulepack_kernel_app_pw")
            assertTheRegistrationStoresTheFourthArgumentInPackVersion(app)
            assertTheCodegenVersionColumnPredicateFindsNothing(container)
        } finally {
            container.stop()
        }
    }

    /** The table has exactly the 15 columns, `version` renamed `pack_version`, every type unchanged (INV-Q-7438). */
    private fun assertTheColumnsAreTheRenamedSet(container: RegistryPostgres) {
        val actual = columnTypes(container, "rule_pack_version")
        assertColumnSet(actual)
        assertEquals("text", actual["pack_version"])
        assertFailsTheColumnSet(actual - "pack_version" + ("version" to "text"))
        assertFailsTheColumnSet(actual + ("version" to "text"))
        assertFailsTheColumnSet(actual - "pack_version" + ("pack_versions" to "text"))
        assertFailsTheColumnSet(actual - "pack_version" + ("packversion" to "text"))
        assertFailsTheColumnSet(actual + ("pack_version" to "character varying"))
    }

    private fun columnTypes(
        container: RegistryPostgres,
        table: String,
    ): Map<String, String> =
        TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD).unbound {
            fetchRows(
                "select column_name, data_type from information_schema.columns " +
                    "where table_schema = 'rulepack_test' and table_name = ?",
                table,
            ).associate { it.getValue("column_name") as String to it.getValue("data_type") as String }
        }

    private fun assertColumnSet(actual: Map<String, String>) {
        assertEquals(EXPECTED_COLUMNS.keys, actual.keys)
        assertEquals(EXPECTED_COLUMNS, actual)
    }

    private fun assertFailsTheColumnSet(actual: Map<String, String>) {
        assertThrows(AssertionError::class.java) { assertColumnSet(actual) }
    }

    /** The lineage key reads UNIQUE (pack_type, tenant_id, pack_version); the resolution index is unchanged. */
    private fun assertTheLineageKeyDefinitionAndTheResolutionIndex(container: RegistryPostgres) {
        TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD).unbound {
            assertEquals(
                "UNIQUE (pack_type, tenant_id, pack_version)",
                fetchValue(
                    "select pg_get_constraintdef(oid) from pg_constraint " +
                        "where conrelid = 'rulepack_test.rule_pack_version'::regclass " +
                        "and conname = 'rule_pack_version_lineage_key'",
                ),
            )
            assertEquals(
                "CREATE INDEX rule_pack_version_resolution_idx ON rulepack_test.rule_pack_version " +
                    "USING btree (pack_type, tenant_id, effective_from)",
                fetchValue(
                    "select indexdef from pg_indexes where schemaname = 'rulepack_test' " +
                        "and indexname = 'rule_pack_version_resolution_idx'",
                ),
            )
        }
    }

    /** A registration with the fourth argument `2026.4` stores it in `pack_version`. */
    private fun assertTheRegistrationStoresTheFourthArgumentInPackVersion(app: TenancyProbe) {
        val id =
            app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc") {
                register(packId = "platform-calver", version = "2026.4")
            }
        val stored =
            app.inSigned(TenantId.PLATFORM) {
                fetchValue("select pack_version from rulepack_test.rule_pack_version where id = ?", id)
            }
        assertEquals("2026.4", stored)
    }

    /**
     * gst.jooq-codegen's predicate (platform-build GenerateJooq.checkVersionColumns, 379e60e) with its three constants
     * spelled out finds no row in the DDL's schema; a throwaway text `version` column in the same schema makes it find
     * one, so the assertion has a case that fails.
     */
    private fun assertTheCodegenVersionColumnPredicateFindsNothing(container: RegistryPostgres) {
        val versionColumn = "version"
        val flywayHistoryTable = "flyway_schema_history"
        val allowedTypes = setOf("integer", "bigint")
        val offending = {
            TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD).unbound {
                fetchRows(
                    "select table_name, data_type from information_schema.columns " +
                        "where table_schema = ? and column_name = ? and table_name <> ?",
                    "rulepack_test",
                    versionColumn,
                    flywayHistoryTable,
                ).filter { it.getValue("data_type") !in allowedTypes }
            }
        }
        assertEquals(0, offending().size)
        val migrate = TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
        try {
            migrate.unbound { fetchRows("create table rulepack_test.codegen_probe (version text)") }
            assertEquals(1, offending().size)
        } finally {
            migrate.unbound { fetchRows("drop table if exists rulepack_test.codegen_probe") }
        }
        assertEquals(0, offending().size)
    }
}

/** The 15 columns of the registry table with their `information_schema` data types (INV-Q-7438). */
private val EXPECTED_COLUMNS: Map<String, String> =
    mapOf(
        "id" to "uuid",
        "pack_id" to "text",
        "pack_type" to "text",
        "pack_version" to "text",
        "tenant_id" to "uuid",
        "effective_from" to "date",
        "effective_to" to "date",
        "min_engine_version" to "text",
        "manifest" to "jsonb",
        "checksums" to "jsonb",
        "signature" to "bytea",
        "contents" to "jsonb",
        "trusted_anchor" to "text",
        "registered_by" to "text",
        "created_at" to "timestamp with time zone",
    )
