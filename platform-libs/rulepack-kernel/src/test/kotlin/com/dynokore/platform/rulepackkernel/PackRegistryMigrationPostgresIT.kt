package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.flywaydb.core.Flyway
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
 * Proves [PackRegistryMigration]'s row-level security and its `register_platform_pack` function against a real
 * PostgreSQL 16, 17 and 18 (CS-19, INV-ADR-3968 items 4a and 6; coordinator ruling INV-Q-4117; INV-T-4137).
 *
 * The schema, roles and generated DDL are applied by Flyway as the container's superuser (the migration owner's
 * position); every assertion runs through tenancy-jooq's [TenancyProbe] logged in as `rulepack_kernel_app`, a
 * `NOSUPERUSER`/`NOBYPASSRLS` runtime login owning no table (the probe itself refuses a superuser or BYPASSRLS
 * session), so every assertion is a real database decision, never an app-level check. Test code names no JDBC type
 * (owner ruling INV-Q-4196; coordinator ruling INV-Q-4140).
 */
@Tag("integration")
class PackRegistryMigrationPostgresIT {
    @ParameterizedTest
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `platform rows are written only by register_platform_pack and read by every bound tenant`(image: String) {
        val container = Postgres(DockerImageName.parse(image))
        container.start()
        try {
            migrate(container)
            val app = TenancyProbe.connect(container.jdbcUrl, "rulepack_kernel_app", "rulepack_kernel_app_pw")
            val privileged =
                app.fetchValue("select rolsuper or rolbypassrls from pg_roles where rolname = current_user")
            assertEquals(false, privileged)
            val tenantA = TenantId(UUID.randomUUID())
            val tenantB = TenantId(UUID.randomUUID())

            assertTheFunctionIsASysOwnedDefinerForTheRuntimeLoginOnly(app, container)
            assertTheFunctionInsertsAPlatformRow(app)
            assertTenantsSeeTheirOwnAndThePlatformRowsOnly(app, tenantA, tenantB)
            assertNoSessionWritesAPlatformRowDirectly(app, tenantA)
            assertTheFunctionRefusesAForgedTenantABlankActorAndBadArguments(app, tenantA)
            assertThePlatformOperatorSettingGrantsNothing(app, tenantA)
            assertNoTenantBoundSeesAndWritesNothing(app, tenantA)
            assertTheLineageKeyIsAPlainUnique(app, tenantA, tenantB)
        } finally {
            container.stop()
        }
    }

    /** Owner `<svc>_sys` (NOLOGIN), SECURITY DEFINER, fixed search_path, EXECUTE for the runtime login only. */
    private fun assertTheFunctionIsASysOwnedDefinerForTheRuntimeLoginOnly(
        app: TenancyProbe,
        container: Postgres,
    ) {
        val row =
            app.unbound {
                fetchRows(
                    "select p.prosecdef as definer, o.rolname as owner, o.rolcanlogin as owner_login, " +
                        "array_to_string(p.proconfig, ';') as config, " +
                        "has_function_privilege('rulepack_kernel_app', p.oid, 'EXECUTE') as app_execute, " +
                        "has_function_privilege('rulepack_kernel_other', p.oid, 'EXECUTE') as other_execute, " +
                        "exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0) as public_execute " +
                        "from pg_proc p join pg_roles o on o.oid = p.proowner " +
                        "where p.proname = ? and p.pronamespace = 'rulepack_test'::regnamespace",
                    PackRegistryMigration.REGISTER_PLATFORM_PACK,
                ).single()
            }
        assertEquals(true, row["definer"])
        assertEquals("rulepack_kernel_sys", row["owner"])
        assertEquals(false, row["owner_login"])
        assertEquals("search_path=pg_catalog, rulepack_test, pg_temp", row["config"])
        assertEquals(true, row["app_execute"])
        assertEquals(false, row["other_execute"])
        assertEquals(false, row["public_execute"])

        val other = TenancyProbe.connect(container.jdbcUrl, "rulepack_kernel_other", "rulepack_kernel_other_pw")
        val denied =
            assertThrows(DataAccessException::class.java) {
                other.inTenant(TenantId.PLATFORM, actor = "svc:rules-svc") { register(packId = "other-login") }
            }
        assertTrue("permission denied" in denied.message.orEmpty(), denied.message)
    }

    /** Under the platform binding with an actor, the function stores a row under TenantId.PLATFORM. */
    private fun assertTheFunctionInsertsAPlatformRow(app: TenancyProbe) {
        val id = app.inTenant(TenantId.PLATFORM, actor = "svc:rules-svc") { register(packId = "platform-scope") }
        val stored =
            app.inTenant(TenantId.PLATFORM) {
                fetchValue("select tenant_id::text from rulepack_test.rule_pack_version where id = ?", id)
            }
        assertEquals(TenantId.PLATFORM.toString(), stored)
    }

    /**
     * A tenant reads its own rows plus the platform rows, never another tenant's; the platform binding reads only the
     * platform rows.
     */
    private fun assertTenantsSeeTheirOwnAndThePlatformRowsOnly(
        app: TenancyProbe,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        insert(app, tenantA, rowTenant = tenantA, packId = "a-own-pack")
        insert(app, tenantB, rowTenant = tenantB, packId = "b-own-pack")

        assertEquals(setOf("platform-scope", "a-own-pack"), packIds(app, Identity.Tenant(tenantA)))
        assertEquals(setOf("platform-scope", "b-own-pack"), packIds(app, Identity.Tenant(tenantB)))
        assertEquals(setOf("platform-scope"), packIds(app, Identity.Tenant(TenantId.PLATFORM)))
    }

    /** INSERT, UPDATE and DELETE of a platform row are refused for a tenant and under the platform binding alike. */
    private fun assertNoSessionWritesAPlatformRowDirectly(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertRefusedByPolicy { insert(app, tenantA, rowTenant = TenantId.PLATFORM, packId = "smuggled") }
        assertRefusedByPolicy { insert(app, TenantId.PLATFORM, rowTenant = TenantId.PLATFORM, packId = "direct") }
        assertRefusedByPolicy { insert(app, tenantA, rowTenant = TenantId(UUID.randomUUID()), packId = "other") }
        for (session in listOf(tenantA, TenantId.PLATFORM)) {
            val changed =
                app.inTenant(session) {
                    fetchValue(
                        "with u as (update rulepack_test.rule_pack_version set pack_id = 'changed' " +
                            "where tenant_id = ?::uuid returning 1) select count(*) from u",
                        TenantId.PLATFORM.toString(),
                    )
                }
            val deleted =
                app.inTenant(session) {
                    fetchValue(
                        "with d as (delete from rulepack_test.rule_pack_version " +
                            "where tenant_id = ?::uuid returning 1) select count(*) from d",
                        TenantId.PLATFORM.toString(),
                    )
                }
            assertEquals(0L, changed, "a session bound to $session changed a platform row")
            assertEquals(0L, deleted, "a session bound to $session deleted a platform row")
        }
        assertTrue("platform-scope" in packIds(app, Identity.Tenant(tenantA)), "the platform row is unchanged")
        assertFalse("changed" in packIds(app, Identity.Tenant(tenantA)))
    }

    /**
     * A tenant session cannot register a platform pack (the function is bound to the platform tenant, so a tenant
     * cannot forge one), and the function refuses a blank actor and every invalid argument.
     */
    private fun assertTheFunctionRefusesAForgedTenantABlankActorAndBadArguments(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertRefusedWith("platform tenant only") {
            app.inTenant(tenantA, actor = "user:a") { register(packId = "forged-by-a") }
        }
        assertRefusedWith("platform tenant only") { app.unbound { register(packId = "forged-unbound") } }
        assertRefusedWith("app.actor is not set") {
            app.inTenant(TenantId.PLATFORM, actor = " ") { register(packId = "no-actor") }
        }
        val platform = { block: TenancyProbe.() -> Any? -> app.inTenant(TenantId.PLATFORM, "svc:rules-svc", block) }
        assertRefusedWith("not blank") { platform { register(packId = " ") } }
        assertRefusedWith("not blank") { platform { register(packId = "p", version = "") } }
        assertRefusedWith("after effective_from") {
            platform { register(packId = "p", effectiveTo = LocalDate.now().minusDays(1)) }
        }
        assertRefusedWith("JSON objects") { platform { register(packId = "p", manifest = "[]") } }
        assertRefusedWith("JSON objects") { platform { register(packId = "p", signature = byteArrayOf()) } }
        assertEquals(setOf("platform-scope"), packIds(app, Identity.Tenant(TenantId.PLATFORM)))
    }

    /** `set_config('app.platform_operator', 'true', true)` grants nothing: no read, no platform write. */
    private fun assertThePlatformOperatorSettingGrantsNothing(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertEquals(emptySet<String>(), packIds(app, Identity.Operator(null)))
        assertEquals(setOf("platform-scope", "a-own-pack"), packIds(app, Identity.Operator(tenantA)))
        assertRefusedByPolicy {
            app.bound(Identity.Operator(null)) { insertRow(TenantId.PLATFORM, "operator-unbound") }
        }
        assertRefusedByPolicy {
            app.bound(Identity.Operator(tenantA)) { insertRow(TenantId.PLATFORM, "operator-as-a") }
        }
    }

    /** No tenant bound: nothing visible and nothing writable (CS-19 fail-closed). */
    private fun assertNoTenantBoundSeesAndWritesNothing(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertEquals(emptySet<String>(), packIds(app, Identity.None))
        assertRefusedByPolicy { app.unbound { insertRow(TenantId.PLATFORM, "unbound-platform") } }
        assertRefusedByPolicy { app.unbound { insertRow(tenantA, "unbound-claiming-a") } }
    }

    /** The lineage key is UNIQUE (pack_type, tenant_id, version): a platform version once, a tenant's per tenant. */
    private fun assertTheLineageKeyIsAPlainUnique(
        app: TenancyProbe,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        app.inTenant(TenantId.PLATFORM, actor = "svc:rules-svc") { register(packId = "platform-v3", version = "3.0.0") }
        assertRefusedWith("rule_pack_version_lineage_key") {
            app.inTenant(TenantId.PLATFORM, actor = "svc:rules-svc") {
                register(packId = "platform-v3-again", version = "3.0.0")
            }
        }
        insert(app, tenantA, rowTenant = tenantA, packId = "a-v2", version = "2.0.0")
        insert(app, tenantB, rowTenant = tenantB, packId = "b-v2", version = "2.0.0")
        assertRefusedWith("rule_pack_version_lineage_key") {
            insert(app, tenantA, rowTenant = tenantA, packId = "a-v2-again", version = "2.0.0")
        }
    }

    /** Who the probe session is: a bound tenant, no tenant, or either with `app.platform_operator = 'true'` set. */
    private sealed interface Identity {
        data class Tenant(val tenant: TenantId) : Identity

        data class Operator(val tenant: TenantId?) : Identity

        data object None : Identity
    }

    private fun <T> TenancyProbe.bound(
        identity: Identity,
        block: TenancyProbe.() -> T,
    ): T = when (identity) {
        is Identity.Tenant -> {
            inTenant(identity.tenant, block = block)
        }

        is Identity.Operator -> {
            val withSetting: TenancyProbe.() -> T = {
                fetchValue("select set_config(?, ?, true)", "app.platform_operator", "true")
                block()
            }
            identity.tenant?.let { inTenant(it, block = withSetting) } ?: unbound(withSetting)
        }

        Identity.None -> {
            unbound(block)
        }
    }

    /** Calls `register_platform_pack` in the current probe transaction; returns the stored id. */
    private fun TenancyProbe.register(
        packId: String,
        version: String = "1.0.0",
        effectiveTo: LocalDate? = null,
        manifest: String = "{}",
        signature: ByteArray = byteArrayOf(1, 2, 3),
    ): Any? = fetchValue(
        "select rulepack_test.register_platform_pack(?::uuid, ?, 'Scope', ?, ?::date, ?::date, '1.0.0', " +
            "?::jsonb, '{}'::jsonb, ?::bytea, '{}'::jsonb, 'test-anchor')",
        UUID.randomUUID(),
        packId,
        version,
        LocalDate.now(),
        effectiveTo,
        manifest,
        signature,
    )

    /** Inserts one row directly in the current probe transaction, the row carrying [rowTenant]. */
    private fun TenancyProbe.insertRow(
        rowTenant: TenantId,
        packId: String,
        version: String = "1.0.0",
    ) {
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
            rowTenant.toString(),
            LocalDate.now(),
            byteArrayOf(1, 2, 3),
        )
    }

    private fun insert(
        app: TenancyProbe,
        session: TenantId,
        rowTenant: TenantId,
        packId: String,
        version: String = "1.0.0",
    ) = app.inTenant(session) { insertRow(rowTenant, packId, version) }

    private fun packIds(
        app: TenancyProbe,
        identity: Identity,
    ): Set<String> = app.bound(identity) {
        fetchRows("select pack_id from rulepack_test.rule_pack_version where version = '1.0.0'")
            .map { it.getValue("pack_id") as String }
            .toSet()
    }

    private fun assertRefusedByPolicy(block: () -> Unit) = assertRefusedWith("row-level security policy", block)

    private fun assertRefusedWith(
        expected: String,
        block: () -> Unit,
    ) {
        val refused = assertThrows(DataAccessException::class.java, block)
        val message = refused.message.orEmpty()
        assertTrue(expected in message, "expected '$expected' in: $message")
    }

    private fun migrate(container: Postgres) {
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/pack-registry-it/base")
            .placeholders(
                mapOf(
                    "packRegistryDdl" to
                        PackRegistryMigration.ddl(
                            schema = "rulepack_test",
                            table = "rule_pack_version",
                            runtimeRole = "rulepack_kernel_app",
                            sysRole = "rulepack_kernel_sys",
                        ),
                ),
            ).load()
            .migrate()
    }
}
