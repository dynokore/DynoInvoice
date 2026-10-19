package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TableClasses
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
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
private class Postgres(
    image: DockerImageName,
) : PostgreSQLContainer<Postgres>(image)

/**
 * Proves [PackRegistryMigration]'s row-level security and its `register_platform_pack` function against a real
 * PostgreSQL 16, 17 and 18 (CS-19, INV-ADR-3968 items 4a and 6; coordinator ruling INV-Q-4117; INV-T-4137).
 *
 * The roles and schemas come from the superuser init script `db/rulepack-kernel-test-roles.sql`, followed by the
 * generated DDL, applied as container init SQL under `SET ROLE rulepack_kernel_migrate`, the
 * `NOSUPERUSER`/`NOBYPASSRLS` migration login that owns the table
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
        val container = Postgres(DockerImageName.parse(image)).withPackRegistryInit()
        container.start()
        try {
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
            val owner = TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
            assertThePlatformOperatorSettingGrantsNothing(app, owner, tenantA)
            assertNoTenantBoundSeesAndWritesNothing(app, owner, tenantA)
            assertAForgedBindingGetsTheRefusalOfTheUnboundCase(app, owner, tenantA)
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
        // The signed-binding key table of the same schema is platform-scoped (no tenant data); the holder declares it.
        val keyTable = PackRegistryMigration.referenceTable("rulepack_test", "binding_key")
        val tables = TableClasses.of(platformScoped = listOf(keyTable), reference = listOf(reference)).verify(schema)
        assertEquals(
            mapOf("rule_pack_version" to TableClass.REFERENCE, "binding_key" to TableClass.PLATFORM_SCOPED),
            schema.tables.associate { it.name to tables.classOf(it.qualifiedName) },
        )

        val wrong = PackRegistryMigration.referenceTable("rulepack_test", "rule_pack_versions")
        val refused =
            assertThrows(IllegalStateException::class.java) {
                TableClasses.of(platformScoped = listOf(keyTable), reference = listOf(wrong)).verify(schema)
            }
        val message = refused.message.orEmpty()
        assertTrue("rulepack_test.rule_pack_versions is declared but missing" in message, message)

        val undeclared = TableClasses.of(platformScoped = listOf(keyTable)).verify(schema)
        assertEquals(
            TableClass.TENANT,
            undeclared.classOf(schema.tables.single { it.name == "rule_pack_version" }.qualifiedName),
        )
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
                other.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc") { register(packId = "other-login") }
            }
        assertTrue("permission denied" in denied.message.orEmpty(), denied.message)
    }

    /**
     * Under the platform binding with an actor, the function stores a row under TenantId.PLATFORM whose registered_by
     * is the actor of the tenant binding of tenancy-kernel (INV-Q-4580).
     */
    private fun assertTheFunctionInsertsAPlatformRow(app: TenancyProbe) {
        val id = app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc") { register(packId = "platform-scope") }
        val stored =
            app.inSigned(TenantId.PLATFORM) {
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
        // INV-Q-6667: the runtime login's platform-row write is also refused under an unsigned plain-settings binding
        // and under a forged signature (the actor trigger, which runs before the policy, refuses both: no actor is
        // bound), and nothing is written.
        assertRefusedWith("the bound actor is not set") {
            app.unbound {
                bindUnsigned(TenantId.PLATFORM)
                insertRow(TenantId.PLATFORM, "unsigned-platform", registeredBy = "tenancy-probe")
            }
        }
        assertRefusedWith("the bound actor is not set") {
            app.inSigned(TenantId.PLATFORM, key = FORGED_KEY) {
                insertRow(TenantId.PLATFORM, "forged-platform", registeredBy = "tenancy-probe")
            }
        }
        for (session in listOf(tenantA, TenantId.PLATFORM)) {
            val changed =
                app.inSigned(session) {
                    fetchValue(
                        "with u as (update rulepack_test.rule_pack_version set pack_id = 'changed' " +
                            "where tenant_id = ?::uuid returning 1) select count(*) from u",
                        TenantId.PLATFORM.toString(),
                    )
                }
            val deleted =
                app.inSigned(session) {
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
            app.inSigned(tenantA, actor = "user:a") { register(packId = "forged-by-a") }
        }
        assertRefusedWith("platform tenant only") { app.unbound { register(packId = "forged-unbound") } }
        assertRefusedWith("the bound actor is not set") {
            app.inSigned(TenantId.PLATFORM, actor = " ") { register(packId = "no-actor") }
        }
        assertRefusedWith("the bound actor is not set") {
            app.unbound {
                bindTenantOnly(TenantId.PLATFORM)
                register(packId = "unset-actor")
            }
        }
        val platform = { block: TenancyProbe.() -> Any? ->
            app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc", block = block)
        }
        assertRefusedWith("not blank") { platform { register(packId = " ") } }
        assertRefusedWith("not blank") { platform { register(packId = "p", version = "") } }
        assertRefusedWith("after effective_from") {
            platform { register(packId = "p", effectiveTo = LocalDate.now().minusDays(1)) }
        }
        val badManifest = PackPayload(manifest = "[]")
        val emptySignature = PackPayload(signature = byteArrayOf())
        assertRefusedWith("JSON objects") { platform { register(packId = "p", payload = badManifest) } }
        assertRefusedWith("JSON objects") { platform { register(packId = "p", payload = emptySignature) } }
        assertEquals(setOf("platform-scope"), packIds(app, Identity.Tenant(TenantId.PLATFORM)))
    }

    /** `set_config('app.platform_operator', 'true', true)` grants nothing: no read, no platform write. */
    private fun assertThePlatformOperatorSettingGrantsNothing(
        app: TenancyProbe,
        owner: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertEquals(emptySet<String>(), packIds(app, Identity.Operator(null)))
        assertEquals(setOf("platform-scope", "a-own-pack"), packIds(app, Identity.Operator(tenantA)))
        assertRefusedByPolicy {
            owner.bound(
                Identity.Operator(null),
            ) { insertBypassingTheActorTrigger(tenantA, "operator-unbound") }
        }
        assertRefusedByPolicy {
            app.bound(Identity.Operator(tenantA)) { insertRow(TenantId.PLATFORM, "operator-as-a") }
        }
    }

    /** No tenant bound: nothing visible and nothing writable (CS-19 fail-closed). */
    private fun assertNoTenantBoundSeesAndWritesNothing(
        app: TenancyProbe,
        owner: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertEquals(emptySet<String>(), packIds(app, Identity.None))
        // Replaced cases (INV-Q-6667): "actor bound, tenant unbound" cannot be written, because a signed binding
        // carries tenant and actor together (the first part must be a uuid), so no signature binds an actor alone.
        // The owner-login trigger-bypass probes below replace them: the policy is reached with the actor trigger
        // out of the way (the owner's fixture function, rolled back); the runtime login's insert is refused by the
        // actor rule first, before the policy. The owner is a member of the sys role, whose own policy admits a
        // platform row, so the owner probes carry a tenant row; a platform row is refused by the policy for the
        // runtime login under every binding (assertNoSessionWritesAPlatformRowDirectly).
        assertRefusedByPolicy { owner.unbound { insertBypassingTheActorTrigger(tenantA, "unbound-claiming-a") } }
        assertRefusedWith("the bound actor is not set") {
            app.unbound { insertRow(TenantId.PLATFORM, "unbound-platform", registeredBy = "tenancy-probe") }
        }
    }

    /**
     * INV-T-5364 (INV-REQ-854 b7): a forged `app.binding_sig` (signed with a key no schema holds, a key of the look-
     * alike schema `rulepack_other`, or a plain `app.tenant_id` and `app.actor` without any signature) gets the
     * refusal the unbound case gets: `register_platform_pack` ("platform tenant only"), the insert trigger ("the bound
     * actor is not set") and the policies (nothing visible, nothing writable). The look-alike: a binding signed with
     * `rulepack_other`'s key is valid for `rulepack_other.register_platform_pack` and refused by `rulepack_test`'s,
     * and the converse.
     */
    private fun assertAForgedBindingGetsTheRefusalOfTheUnboundCase(
        app: TenancyProbe,
        owner: TenancyProbe,
        tenantA: TenantId,
    ) {
        for (key in listOf(FORGED_KEY, OTHER_KEY)) {
            assertRefusedWith("platform tenant only") {
                app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc", key = key) { register(packId = "forged-fn") }
            }
            assertRefusedWith("the bound actor is not set") {
                app.inSigned(tenantA, actor = "user:a", key = key) {
                    insertRow(tenantA, "forged-row", registeredBy = "user:a")
                }
            }
            assertEquals(emptySet<String>(), app.inSigned(tenantA, actor = "user:a", key = key) { visiblePackIds() })
        }
        assertRefusedWith("platform tenant only") {
            app.unbound {
                bindUnsigned(TenantId.PLATFORM, "svc:rules-svc")
                register(packId = "unsigned-fn")
            }
        }
        assertRefusedWith("the bound actor is not set") {
            app.unbound {
                bindUnsigned(tenantA, "user:a")
                insertRow(tenantA, "unsigned-row", registeredBy = "user:a")
            }
        }
        val unsignedVisible =
            app.unbound {
                bindUnsigned(tenantA, "user:a")
                visiblePackIds()
            }
        assertEquals(emptySet<String>(), unsignedVisible)
        assertRefusedByPolicy {
            owner.inSigned(tenantA, actor = "user:a", key = FORGED_KEY) {
                insertBypassingTheActorTrigger(tenantA, "forged-owner-row")
            }
        }

        assertTheLookAlikeSchemaKeyIsNotThisSchemasKey(app)
    }

    /** Look-alike schema `rulepack_other`: its key is not this schema's key, in both directions. */
    private fun assertTheLookAlikeSchemaKeyIsNotThisSchemasKey(app: TenancyProbe) {
        val id =
            app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc", key = OTHER_KEY) {
                register(packId = "other-schema-pack", schema = "rulepack_other")
            }
        assertTrue(id != null)
        assertRefusedWith("platform tenant only") {
            app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc", key = KEY) {
                register(packId = "other-schema-pack-2", schema = "rulepack_other")
            }
        }
        assertEquals(setOf("platform-scope"), packIds(app, Identity.Tenant(TenantId.PLATFORM)))
    }

    /** The lineage key is UNIQUE (pack_type, tenant_id, version): a platform version once, a tenant's per tenant. */
    private fun assertTheLineageKeyIsAPlainUnique(
        app: TenancyProbe,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc") { register(packId = "platform-v3", version = "3.0.0") }
        assertRefusedWith("rule_pack_version_lineage_key") {
            app.inSigned(TenantId.PLATFORM, actor = "svc:rules-svc") {
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
     * registered_by is the transaction's bound actor (tenancy-kernel's tenant binding) at insert and never changes
     * (owner ruling INV-Q-4580 (B)): a tenant insert naming another principal, or with the actor unset or blank, is
     * refused and writes nothing.
     */
    private fun assertRegisteredByIsTheActorAtInsert(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        app.inSigned(tenantA, actor = "user:a") {
            insertRow(tenantA, "a-by-actor", version = "4.0.0", registeredBy = "user:a")
        }
        assertRefusedWith("registered_by must be app.actor") {
            app.inSigned(tenantA, actor = "user:a") {
                insertRow(tenantA, "a-forged", version = "4.0.1", registeredBy = "user:someone-else")
            }
        }
        assertRefusedWith("the bound actor is not set") {
            app.inSigned(tenantA, actor = " ") {
                insertRow(tenantA, "a-blank-actor", version = "4.0.2", registeredBy = " ")
            }
        }
        assertRefusedWith("the bound actor is not set") {
            app.unbound {
                bindTenantOnly(tenantA)
                insertRow(tenantA, "a-unset-actor", version = "4.0.3", registeredBy = "user:a")
            }
        }
    }

    /**
     * An UPDATE of registered_by is refused on a tenant row by the trigger and finds no platform row under the policy
     * (owner ruling INV-Q-4580 (B)); only the rows inserted with registered_by = the bound actor exist.
     */
    private fun assertRegisteredByCannotBeUpdated(
        app: TenancyProbe,
        tenantA: TenantId,
    ) {
        assertRefusedWith("registered_by is fixed at insert") {
            app.inSigned(tenantA, actor = "user:b") {
                fetchValue(
                    "update rulepack_test.rule_pack_version set registered_by = 'user:b' " +
                        "where pack_id = 'a-by-actor' returning 1",
                )
            }
        }
        val platformChanged =
            app.inSigned(TenantId.PLATFORM, actor = "user:b") {
                fetchValue(
                    "with u as (update rulepack_test.rule_pack_version set registered_by = 'user:b' " +
                        "where tenant_id = ?::uuid returning 1) select count(*) from u",
                    TenantId.PLATFORM.toString(),
                )
            }
        assertEquals(0L, platformChanged)
        val stored =
            app.inSigned(tenantA) {
                fetchRows(
                    "select pack_id, registered_by from rulepack_test.rule_pack_version " +
                        "where pack_id in ('a-by-actor', 'platform-scope', 'a-forged', 'a-blank-actor', " +
                        "'a-unset-actor')",
                ).associate { it["pack_id"] as String to it["registered_by"] as String }
            }
        assertEquals(mapOf("a-by-actor" to "user:a", "platform-scope" to "svc:rules-svc"), stored)
    }
}
