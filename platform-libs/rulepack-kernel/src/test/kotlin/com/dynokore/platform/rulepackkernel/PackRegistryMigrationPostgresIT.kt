package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TableClasses
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.flywaydb.core.Flyway
import org.jooq.Record
import org.jooq.Table
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.SchemaImpl
import org.jooq.impl.TableImpl
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

/**
 * The migrated schema in the shape jOOQ's code generator emits (a SchemaImpl and TableImpl subclasses, as
 * tenancy-jooq's own TestSchema), built from the catalog's tables and columns as the migration left them, so
 * [TableClasses.verify] judges the real migrated table. Column types are not read: the verification looks only at
 * table names and the tenant column's presence.
 */
private class MigratedSchema(
    name: String,
    columnsByTable: Map<String, List<String>>,
) : SchemaImpl(DSL.name(name), null) {
    private val tables: List<Table<*>> = columnsByTable.map { (table, columns) -> MigratedTable(table, columns, this) }

    override fun getTables(): List<Table<*>> = tables
}

private class MigratedTable(
    name: String,
    columns: List<String>,
    schema: MigratedSchema,
) : TableImpl<Record>(DSL.name(name), schema) {
    init {
        columns.forEach { createField(DSL.name(it), SQLDataType.OTHER, this) }
    }
}

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(image: DockerImageName) : PostgreSQLContainer<Postgres>(image)

/**
 * Proves [PackRegistryMigration]'s row-level security and its `register_platform_pack` function against a real
 * PostgreSQL 16, 17 and 18 (CS-19, INV-ADR-3968 items 4a and 6; coordinator ruling INV-Q-4117; INV-T-4137).
 *
 * The roles and schemas come from the superuser init script `db/rulepack-kernel-test-roles.sql`; the generated DDL is
 * applied by Flyway as `rulepack_kernel_migrate`, the `NOSUPERUSER`/`NOBYPASSRLS` migration login that owns the table
 * and is a member of `rulepack_kernel_sys` (it hands the function over), as every other module's IT migrates. The
 * migrated schema verifies under tenancy-jooq's [TableClasses] with [PackRegistryMigration.referenceTable] declared
 * REFERENCE (coordinator ruling INV-Q-4582). Every assertion runs through tenancy-jooq's [TenancyProbe] logged in as
 * `rulepack_kernel_app`, a `NOSUPERUSER`/`NOBYPASSRLS` runtime login owning no table (the probe itself refuses a
 * superuser or BYPASSRLS session), so every assertion is a real database decision, never an app-level check. Test
 * code names no JDBC type (owner ruling INV-Q-4196; coordinator ruling INV-Q-4140).
 */
@Tag("integration")
class PackRegistryMigrationPostgresIT {
    @ParameterizedTest
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `platform rows are written only by register_platform_pack and read by every bound tenant`(image: String) {
        val container = Postgres(DockerImageName.parse(image)).withInitScript("db/rulepack-kernel-test-roles.sql")
        container.start()
        try {
            migrate(container)
            assertTheMigrationRoleOwnsTheTableWithoutPrivilege(container)
            assertTheTableClassCheckOfTheMigratedSchema(container)
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
            assertRegisteredByIsTheActorAtInsert(app, tenantA)
            assertRegisteredByCannotBeUpdated(app, tenantA)
            assertTheLineageKeyIsAPlainUnique(app, tenantA, tenantB)
        } finally {
            container.stop()
        }
    }

    /** The table is owned by the NOSUPERUSER, NOBYPASSRLS migration login, RLS enabled and forced (done-when 2). */
    private fun assertTheMigrationRoleOwnsTheTableWithoutPrivilege(container: Postgres) {
        val migrate = TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
        val row =
            migrate
                .unbound {
                    fetchRows(
                        "select o.rolname as owner, o.rolsuper or o.rolbypassrls as privileged, " +
                            "c.relrowsecurity as rls, c.relforcerowsecurity as forced " +
                            "from pg_class c join pg_roles o on o.oid = c.relowner " +
                            "where c.oid = 'rulepack_test.rule_pack_version'::regclass",
                    )
                }.single()
        assertEquals(MIGRATE, row["owner"])
        assertEquals(false, row["privileged"])
        assertEquals(true, row["rls"])
        assertEquals(true, row["forced"])
    }

    /**
     * tenancy-jooq's table-class check of the migrated schema (INV-ADR-3968 item 4a; coordinator rulings INV-Q-4582
     * and INV-Q-4596): declared via [PackRegistryMigration.referenceTable], the verification passes and classifies the
     * table REFERENCE (a bound tenant's reads take its own rows plus the platform rows); a wrongly declared name fails
     * (declared but missing); undeclared, the table is classified TENANT (the item 4a default, under which tenant
     * reads silently drop the platform rows: the holder must declare it, INV-MOD-53).
     */
    private fun assertTheTableClassCheckOfTheMigratedSchema(container: Postgres) {
        val columns =
            TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD).unbound {
                fetchRows(
                    "select table_name, column_name from information_schema.columns " +
                        "where table_schema = 'rulepack_test' order by table_name, ordinal_position",
                )
            }
        val schema =
            MigratedSchema(
                "rulepack_test",
                columns.groupBy({ it["table_name"] as String }, { it["column_name"] as String }),
            )
        val reference = PackRegistryMigration.referenceTable("rulepack_test", "rule_pack_version")
        val tables = TableClasses.of(reference = listOf(reference)).verify(schema)
        assertEquals(listOf(TableClass.REFERENCE), schema.tables.map { tables.classOf(it.qualifiedName) })

        val wrong = PackRegistryMigration.referenceTable("rulepack_test", "rule_pack_versions")
        val refused =
            assertThrows(IllegalStateException::class.java) {
                TableClasses.of(reference = listOf(wrong)).verify(schema)
            }
        val message = refused.message.orEmpty()
        assertTrue("rulepack_test.rule_pack_versions is declared but missing" in message, message)

        val undeclared = TableClasses.of().verify(schema)
        assertEquals(listOf(TableClass.TENANT), schema.tables.map { undeclared.classOf(it.qualifiedName) })
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

    /**
     * Under the platform binding with an actor, the function stores a row under TenantId.PLATFORM whose registered_by
     * is `app.actor` (INV-Q-4580).
     */
    private fun assertTheFunctionInsertsAPlatformRow(app: TenancyProbe) {
        val id = app.inTenant(TenantId.PLATFORM, actor = "svc:rules-svc") { register(packId = "platform-scope") }
        val stored =
            app.inTenant(TenantId.PLATFORM) {
                fetchRows(
                    "select tenant_id::text as tenant, registered_by from rulepack_test.rule_pack_version where id = ?",
                    id,
                ).single()
            }
        assertEquals(TenantId.PLATFORM.toString(), stored["tenant"])
        assertEquals("svc:rules-svc", stored["registered_by"])
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
        assertRefusedWith("app.actor is not set") {
            app.unbound {
                bindTenantOnly(TenantId.PLATFORM)
                register(packId = "unset-actor")
            }
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
            app.bound(Identity.Operator(null)) {
                bindActorOnly()
                insertRow(TenantId.PLATFORM, "operator-unbound")
            }
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
        assertRefusedByPolicy {
            app.unbound {
                bindActorOnly()
                insertRow(TenantId.PLATFORM, "unbound-platform")
            }
        }
        assertRefusedByPolicy {
            app.unbound {
                bindActorOnly()
                insertRow(tenantA, "unbound-claiming-a")
            }
        }
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

    /**
     * registered_by is the transaction's `app.actor` at insert and never changes (owner ruling INV-Q-4580 (B)): a
     * tenant insert naming another principal, or with `app.actor` unset or blank, is refused and writes nothing.
     */
    private fun assertRegisteredByIsTheActorAtInsert(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        app.inTenant(tenantA, actor = "user:a") {
            insertRow(tenantA, "a-by-actor", version = "4.0.0", registeredBy = "user:a")
        }
        assertRefusedWith("registered_by must be app.actor") {
            app.inTenant(tenantA, actor = "user:a") {
                insertRow(tenantA, "a-forged", version = "4.0.1", registeredBy = "user:someone-else")
            }
        }
        assertRefusedWith("app.actor is not set") {
            app.inTenant(tenantA, actor = " ") {
                insertRow(tenantA, "a-blank-actor", version = "4.0.2", registeredBy = " ")
            }
        }
        assertRefusedWith("app.actor is not set") {
            app.unbound {
                bindTenantOnly(tenantA)
                insertRow(tenantA, "a-unset-actor", version = "4.0.3", registeredBy = "user:a")
            }
        }
    }

    /**
     * An UPDATE of registered_by is refused on a tenant row by the trigger and finds no platform row under the policy
     * (owner ruling INV-Q-4580 (B)); only the rows inserted with registered_by = `app.actor` exist.
     */
    private fun assertRegisteredByCannotBeUpdated(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertRefusedWith("registered_by is fixed at insert") {
            app.inTenant(tenantA, actor = "user:b") {
                fetchValue(
                    "update rulepack_test.rule_pack_version set registered_by = 'user:b' " +
                        "where pack_id = 'a-by-actor' returning 1",
                )
            }
        }
        val platformChanged =
            app.inTenant(TenantId.PLATFORM, actor = "user:b") {
                fetchValue(
                    "with u as (update rulepack_test.rule_pack_version set registered_by = 'user:b' " +
                        "where tenant_id = ?::uuid returning 1) select count(*) from u",
                    TenantId.PLATFORM.toString(),
                )
            }
        assertEquals(0L, platformChanged)
        val stored =
            app.inTenant(tenantA) {
                fetchRows(
                    "select pack_id, registered_by from rulepack_test.rule_pack_version " +
                        "where pack_id in ('a-by-actor', 'platform-scope', 'a-forged', 'a-blank-actor', " +
                        "'a-unset-actor')",
                ).associate { it["pack_id"] as String to it["registered_by"] as String }
            }
        assertEquals(mapOf("a-by-actor" to "user:a", "platform-scope" to "svc:rules-svc"), stored)
    }

    private fun migrate(container: Postgres) {
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
            .schemas("rulepack_migrations")
            .createSchemas(false)
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

    private companion object {
        const val MIGRATE = "rulepack_kernel_migrate"
        const val MIGRATE_PASSWORD = "rulepack_kernel_migrate_pw"
    }
}
