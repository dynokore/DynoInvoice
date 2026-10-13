package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.util.UUID

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(image: DockerImageName) : PostgreSQLContainer<Postgres>(image)

/**
 * Proves [PackRegistryMigration]'s row-level-security policy against a real
 * PostgreSQL (review should-fix, then escalated must-fix, on this MR, CS-19):
 * a text assertion on the generated SQL cannot catch a policy that is
 * well-formed but wrong, as both the original symmetric `WITH CHECK` and the
 * later "no tenant bound treated as the platform identity" version were.
 *
 * The schema, the generated DDL and the runtime role are applied by Flyway as the container's superuser (the
 * migration owner's position); every RLS assertion runs through tenancy-jooq's [TenancyProbe] logged in as
 * `rulepack_kernel_app`, the same kind of INSERT/SELECT-only, `NOSUPERUSER`/`NOBYPASSRLS` role a real pack holder's
 * runtime uses (the probe itself refuses a superuser or BYPASSRLS session), so every assertion below is a real RLS
 * decision, never an app-level check. Test code names no JDBC type (owner ruling INV-Q-4196; INV-T-4188).
 */
@Tag("integration")
class PackRegistryMigrationPostgresIT {
    @ParameterizedTest
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `RLS isolates tenants, admits the platform operator, and fails closed with no identity bound`(image: String) {
        val container = Postgres(DockerImageName.parse(image))
        container.start()
        try {
            migrate(container, BASE)
            val app = TenancyProbe.connect(container.jdbcUrl, "rulepack_kernel_app", "rulepack_kernel_app_pw")
            val privileged =
                app.fetchValue("select rolsuper or rolbypassrls from pg_roles where rolname = current_user")
            assertEquals(false, privileged)

            val tenantA = TenantId(UUID.randomUUID())
            val tenantB = TenantId(UUID.randomUUID())

            assertTenantSeesOwnRowNeverAnothers(app, tenantA, tenantB)
            assertTenantCannotSmuggleAPlatformWideRow(app, tenantA)
            assertPlatformOperatorCanInsertAndReadAPlatformWideRow(app)
            assertNoIdentityBoundFailsClosedOnReadAndWrite(app, tenantA)
            assertLineageKeyRefusesDuplicatePlatformVersionButNotTenantRows(app, tenantA, tenantB)
            assertUpgradeMigrationFixesAnOldConstraint(container, app)
        } finally {
            container.stop()
        }
    }

    /** A tenant can insert its own row; tenant A cannot read tenant B's row. */
    private fun assertTenantSeesOwnRowNeverAnothers(
        app: TenancyProbe,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        insert(app, Identity.Tenant(tenantA), rowTenant = tenantA, packId = "a-own-pack")
        insert(app, Identity.Tenant(tenantB), rowTenant = tenantB, packId = "b-own-pack")

        val visibleToA = selectPackIds(app, Identity.Tenant(tenantA))
        assertTrue(visibleToA.contains("a-own-pack"), "tenant A must see its own row, saw $visibleToA")
        assertFalse(visibleToA.contains("b-own-pack"), "tenant A must never see tenant B's row, saw $visibleToA")
    }

    /**
     * `WITH CHECK` must refuse a tenant-bound session inserting a NULL-tenant
     * row as a database error, not an app-level check — "no tenant bound" is
     * not by itself the platform operator (the must-fix this test guards):
     * only an explicit `app.platform_operator = 'true'` identity may.
     */
    private fun assertTenantCannotSmuggleAPlatformWideRow(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertRefusedByPolicy {
            insert(app, Identity.Tenant(tenantA), rowTenant = null, packId = "smuggled-platform-wide")
        }
    }

    /**
     * The platform operator's own identity (INV-REQ-1343) can insert and read
     * a platform-wide row — the fix must not merely refuse everyone.
     */
    private fun assertPlatformOperatorCanInsertAndReadAPlatformWideRow(app: TenancyProbe) {
        insert(app, Identity.PlatformOperator, rowTenant = null, packId = "operator-platform-wide")
        val visibleToOperator = selectPackIds(app, Identity.PlatformOperator)
        assertTrue(
            visibleToOperator.contains("operator-platform-wide"),
            "the platform operator must see the platform-wide row it inserted, saw $visibleToOperator",
        )
    }

    /**
     * A session with no identity bound at all — no `set_config` call, ever —
     * must see nothing (fail-closed on read, CS-19, the same guarantee
     * `TenancyKernelPostgresIT.assertFailsClosedWithNoTenantBound` proves for
     * tenancy-kernel's own tables) and must not be able to insert anything,
     * including a NULL-tenant row (fail-closed on write too — the must-fix
     * this test guards: absence must never be read as the platform identity).
     */
    private fun assertNoIdentityBoundFailsClosedOnReadAndWrite(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        val visible = selectPackIds(app, Identity.None)
        assertTrue(visible.isEmpty(), "expected no rows visible with no identity bound, got $visible")
        assertRefusedByPolicy { insert(app, Identity.None, rowTenant = null, packId = "unbound-platform-wide") }
        assertRefusedByPolicy { insert(app, Identity.None, rowTenant = tenantA, packId = "unbound-claiming-tenant-a") }
    }

    /** INV-Q-2964: a second platform row with the same pack_type and version is refused; tenant rows are unchanged. */
    private fun assertLineageKeyRefusesDuplicatePlatformVersionButNotTenantRows(
        app: TenancyProbe,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        insert(app, Identity.PlatformOperator, rowTenant = null, packId = "platform-v3", version = "3.0.0")
        assertRefusedByLineageKey {
            insert(app, Identity.PlatformOperator, rowTenant = null, packId = "platform-v3-again", version = "3.0.0")
        }
        // Same pack_type and version in two different tenants stays allowed.
        insert(app, Identity.Tenant(tenantB), rowTenant = tenantB, packId = "b-v2", version = "2.0.0")
        insert(app, Identity.Tenant(tenantA), rowTenant = tenantA, packId = "a-v2", version = "2.0.0")
        // The same tenant and version twice stays refused, as before.
        assertRefusedByLineageKey {
            insert(app, Identity.Tenant(tenantA), rowTenant = tenantA, packId = "a-v2-again", version = "2.0.0")
        }
    }

    /**
     * A holder's existing table (old constraint, [OLD]: the generator's DDL with plain UNIQUE and RLS disabled, holding
     * the same platform-wide version twice) is repaired by the upgrade migration: while the duplicate is present the
     * single-statement upgrade fails and keeps the old constraint; once it is removed the upgrade applies and the
     * duplicate is refused from then on.
     */
    private fun assertUpgradeMigrationFixesAnOldConstraint(
        container: Postgres,
        app: TenancyProbe,
    ) {
        val generatedOld =
            PackRegistryMigration
                .ddl(schema = "rulepack_test", table = "old_pack")
                .replace("UNIQUE NULLS NOT DISTINCT", "UNIQUE")
                .replace("rulepack_test.old_pack ENABLE", "rulepack_test.old_pack DISABLE")
        val committedOld = resource("old/V10__old_pack.sql").normalised()
        assertEquals(generatedOld.normalised(), committedOld, "the old table is the generator's")
        migrate(container, BASE + OLD)

        val failed = assertThrows(FlywayException::class.java) { migrate(container, BASE + OLD + UPGRADE) }
        val failure = failed.message.orEmpty()
        assertTrue("old_pack_lineage_key" in failure, "the upgrade fails on the duplicate: $failure")
        val constraints =
            app.fetchValue(
                "select count(*) from pg_constraint " +
                    "where conname = ? and conrelid = 'rulepack_test.old_pack'::regclass",
                "old_pack_lineage_key",
            )
        assertEquals(1L, constraints, "the old lineage constraint must survive a failed upgrade")

        migrate(container, BASE + OLD + DEDUPE + UPGRADE)
        val again =
            assertThrows(FlywayException::class.java) {
                migrate(container, BASE + OLD + DEDUPE + UPGRADE + AGAIN)
            }.message.orEmpty()
        assertTrue("old_pack_lineage_key" in again, "the upgraded key refuses the duplicate: $again")
    }

    /** Who the probe session is: a bound tenant, the platform operator, or no identity at all. */
    private sealed interface Identity {
        data class Tenant(val tenant: TenantId) : Identity

        data object PlatformOperator : Identity

        data object None : Identity
    }

    /**
     * Runs [block] in one probe transaction bound as [identity]: the tenant as the transaction runner binds it, the
     * platform operator as `app.platform_operator = 'true'` with no tenant, or no `set_config` call at all.
     */
    private fun <T> TenancyProbe.bound(
        identity: Identity,
        block: TenancyProbe.() -> T,
    ): T = when (identity) {
        is Identity.Tenant -> inTenant(identity.tenant, block = block)

        Identity.PlatformOperator ->
            unbound {
                fetchValue("select set_config(?, ?, true)", "app.platform_operator", "true")
                block()
            }

        Identity.None -> unbound(block)
    }

    /** Inserts one row through a session bound as [identity], the row itself carrying [rowTenant]. */
    private fun insert(
        app: TenancyProbe,
        identity: Identity,
        rowTenant: TenantId?,
        packId: String,
        version: String = "1.0.0",
    ) {
        app.bound(identity) {
            fetchValue(
                """
                insert into rulepack_test.rule_pack_version
                    (id, pack_id, pack_type, version, tenant_id, effective_from, min_engine_version,
                     manifest, checksums, signature, contents, trusted_anchor)
                values (?, ?, 'Scope', ?, ?::uuid, ?, '1.0.0', '{}'::jsonb, '{}'::jsonb, ?, '{}'::jsonb, 'test-anchor')
                returning pack_id
                """.trimIndent(),
                UUID.randomUUID(),
                packId,
                version,
                rowTenant?.toString(),
                LocalDate.now(),
                byteArrayOf(1, 2, 3),
            )
        }
    }

    /** Every visible `pack_id` through a session bound as [identity]. */
    private fun selectPackIds(
        app: TenancyProbe,
        identity: Identity,
    ): List<String> = app.bound(identity) {
        fetchRows("select pack_id from rulepack_test.rule_pack_version").map { it.getValue("pack_id") as String }
    }

    private fun assertRefusedByPolicy(block: () -> Unit) {
        val refused = assertThrows(DataAccessException::class.java, block)
        val message = refused.message.orEmpty()
        assertTrue("row-level security policy" in message, "refused by the policy: $message")
    }

    private fun assertRefusedByLineageKey(block: () -> Unit) {
        val refused = assertThrows(DataAccessException::class.java, block)
        val message = refused.message.orEmpty()
        assertTrue("rule_pack_version_lineage_key" in message, "refused by the key: $message")
    }

    private fun migrate(
        container: Postgres,
        locations: List<String>,
    ) {
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations(*locations.map { "classpath:db/pack-registry-it/$it" }.toTypedArray())
            .placeholders(
                mapOf(
                    "packRegistryDdl" to
                        PackRegistryMigration.ddl(schema = "rulepack_test", table = "rule_pack_version"),
                    "lineageKeyUpgradeDdl" to
                        PackRegistryMigration.lineageKeyUpgradeDdl(schema = "rulepack_test", table = "old_pack"),
                ),
            ).load()
            .migrate()
    }

    private fun resource(path: String): String {
        val url = requireNotNull(javaClass.getResource("/db/pack-registry-it/$path")) { path }
        return url.readText()
    }

    /** The statements of [this] SQL without comments, blank lines and indentation. */
    private fun String.normalised(): List<String> {
        val trimmed = lines().map { it.trim() }
        return trimmed.filter { it.isNotEmpty() && !it.startsWith("--") }
    }

    private companion object {
        val BASE = listOf("base")
        val OLD = listOf("old")
        val UPGRADE = listOf("upgrade")
        val DEDUPE = listOf("dedupe")
        val AGAIN = listOf("again")
    }
}
