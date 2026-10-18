package com.dynokore.platform.configresolver

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Testcontainers needs a concrete subclass for its fluent `self()` typing. */
private class Postgres(
    image: DockerImageName,
) : PostgreSQLContainer<Postgres>(image)

/**
 * One PostgreSQL container (16, 17 or 18) holding this module's tables in a fixture service schema `svc`, created at
 * container start by init SQL (route R1, owner ruling INV-Q-4508 (A)): [ConfigSchema.ddl] after the outbox it writes
 * into, as the service's migration login would (`SET ROLE config_owner`). No test issues DDL. Every login reaches the
 * database through [TestPoolFixture], a transaction runner and tenancy-jooq's DSLContext, or [probe]:
 *
 * - [app] (runner), [dsl] and the module's [recorder], [purge] and [reader] are the runtime login `config_app`'s;
 * - [probe] is a `TenancyProbe` on the same login, for catalog reads and row counts under a tenant's binding;
 * - settings are written by [setTenant] as the runtime login inserts them and by [setInstallation] as a row the
 *   installation write function would store, as the owner login under the platform binding (the runtime login has no
 *   grant; the setting operation itself is [JdbcPolicyWriter]'s, tested by the write-path tests).
 */
class ConfigHarness(
    image: String,
    form: DeploymentForm = DeploymentForm.SAAS,
) : AutoCloseable {
    private val container =
        Postgres(DockerImageName.parse(image))
            .withCopyToContainer(Transferable.of(rolesScript()), "/docker-entrypoint-initdb.d/00-config-roles.sql")
            .withCopyToContainer(Transferable.of(schemaScript()), "/docker-entrypoint-initdb.d/10-config-schema.sql")
            .also { it.start() }

    val tenants = TenantContextImpl()
    private val appPool = TestPoolFixture(container.jdbcUrl, "config_app", "config_app_pw", poolName = "config-app")
    private val ownerPool =
        TestPoolFixture(container.jdbcUrl, "config_owner", "config_owner_pw", poolName = "config-owner")
    val app = appPool.runner(tenants, keys = SignedBindingFixture.KEYS)
    private val owner = ownerPool.runner(tenants, keys = SignedBindingFixture.KEYS)
    val dsl: DSLContext = TenancyJooq.dslContext(ConfigTables.declared(SCHEMA), tenants, app)
    private val ownerDsl: DSLContext = TenancyJooq.dslContext(ConfigTables.declared(SCHEMA), tenants, owner)
    val probe: TenancyProbe = TenancyProbe.connect(container.jdbcUrl, "config_app", "config_app_pw")
    val reader = JdbcPolicyReader(dsl, TestCatalogue.catalogue, form, SCHEMA)
    val recorder = JdbcResolutionRecorder(dsl, SCHEMA, CLOCK)
    val purge = JdbcConfigPurge(dsl, SCHEMA)
    val maximum = JdbcOptionValueMaximum(dsl, SCHEMA)

    /** A probe on the migration login, which is a member of `config_sys`: `set_config('role', 'config_sys', true)`. */
    val ownerProbe: TenancyProbe = TenancyProbe.connect(container.jdbcUrl, "config_owner", "config_owner_pw")

    private val setting: Table<*> = DSL.table(DSL.name(SCHEMA, ConfigSchema.SETTING))
    private val installationSetting: Table<*> = DSL.table(DSL.name(SCHEMA, ConfigSchema.INSTALLATION_SETTING))

    /** Binds [tenant] with [actor] (INV-Q-3997, INV-Q-4268). */
    fun <T> asTenant(
        tenant: TenantId,
        actor: String = "user-1",
        block: () -> T,
    ): T = tenants.bind(tenant, actor, block)

    /** Inserts a tenant-and-below setting as the application role, inside the tenant's transaction. */
    fun setTenant(
        tenant: TenantId,
        key: VariationPointKey,
        ref: ScopeRef,
        selected: List<SelectedOption>,
        from: LocalDate,
        entity: String? = null,
        profile: Pair<String, String>? = null,
    ): UUID =
        asTenant(tenant) {
            app.inTransaction {
                val id = UUID.randomUUID()
                dsl
                    .insertInto(setting)
                    .set(
                        mapOf<Field<*>, Any?>(
                            field("id", SQLDataType.UUID) to id,
                            field("tenant_id", SQLDataType.UUID) to tenant.value,
                            field("entity_id", SQLDataType.CLOB) to entity,
                            field("vp_key", SQLDataType.CLOB) to key.value,
                            field("scope_level", SQLDataType.CLOB) to ref.level.name,
                            field("scope_id", SQLDataType.CLOB) to ref.id,
                            OptionColumns.keys to OptionColumns.keysOf(selected),
                            OptionColumns.values to OptionColumns.valuesOf(selected),
                            field("source", SQLDataType.CLOB) to if (profile == null) "OVERRIDE" else "PROFILE",
                            field("profile_id", SQLDataType.CLOB) to profile?.first,
                            field("profile_version", SQLDataType.CLOB) to profile?.second,
                            field("effective_from", SQLDataType.LOCALDATE) to from,
                            field("set_by", SQLDataType.CLOB) to "user-1",
                            field("reason", SQLDataType.CLOB) to "test",
                            field("recorded_at", SQLDataType.TIMESTAMPWITHTIMEZONE) to recordedAt(),
                        ),
                    ).execute()
                id
            }
        }

    /**
     * Inserts an installation setting as the owner login in a platform transaction: the runtime login cannot, and the
     * write function also writes the value's audit entry and PolicyChanged, which resolution does not read.
     */
    fun setInstallation(
        key: VariationPointKey,
        selected: List<SelectedOption>,
        from: LocalDate,
    ): UUID {
        val id = UUID.randomUUID()
        tenants.bindPlatform("operator-1") {
            owner.inTransaction {
                ownerDsl
                    .insertInto(installationSetting)
                    .set(
                        mapOf<Field<*>, Any?>(
                            field("id", SQLDataType.UUID) to id,
                            field("vp_key", SQLDataType.CLOB) to key.value,
                            OptionColumns.keys to OptionColumns.keysOf(selected),
                            OptionColumns.values to OptionColumns.valuesOf(selected),
                            field("effective_from", SQLDataType.LOCALDATE) to from,
                            field("set_by", SQLDataType.CLOB) to "operator-1",
                            field("reason", SQLDataType.CLOB) to "test",
                            field("recorded_at", SQLDataType.TIMESTAMPWITHTIMEZONE) to recordedAt(),
                        ),
                    ).execute()
            }
        }
        return id
    }

    /** The number of rows of [table] that [tenant]'s own binding sees (RLS is forced, so no wider count exists). */
    fun rows(
        table: String,
        tenant: TenantId,
    ): Long = probe.inTenant(tenant) { fetchValue("select count(*) from $SCHEMA.$table") } as Long

    /** The SQLSTATE a refused statement of [block] ends with; fails when [block] does not throw. */
    fun refused(block: () -> Unit): String? {
        val error = runCatching(block).exceptionOrNull() ?: error("the statement was not refused")
        return app.sqlStateOf(error)
    }

    private fun recordedAt(): OffsetDateTime = CLOCK.instant().atOffset(ZoneOffset.UTC)

    private fun <T> field(
        name: String,
        type: DataType<T>,
    ): Field<T> = DSL.field(DSL.name(name), type)

    override fun close() {
        appPool.close()
        ownerPool.close()
        container.stop()
    }

    companion object {
        const val SCHEMA = "svc"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)

        /** The roles and grants, run by the image's init before [schemaScript] (a container init script runs later). */
        private fun rolesScript(): String =
            checkNotNull(ConfigHarness::class.java.getResource("/db/config-resolver-test-roles.sql")) {
                "no roles script"
            }.readText()

        /** The tables, as the migration login `config_owner` creates them; run by the image's init, after the roles. */
        private fun schemaScript(): String =
            SignedBindingFixture.script(SCHEMA, "config_sys", "config_app") + "\n" +
                "SET ROLE config_owner;\n" +
                OutboxSchema.ddl(SCHEMA, "config_app", "config_sys") + "\n" +
                ConfigSchema.ddl(SCHEMA, "config_app", "config_sys") + "\n" +
                "RESET ROLE;\n"
    }
}

/** The two selected-option columns of a table row as jOOQ fields, written the way the test fixtures always did. */
internal object OptionColumns {
    val keys: Field<Array<String>> = DSL.field(DSL.name("option_keys"), SQLDataType.CLOB.array())
    val values: Field<JSONB> = DSL.field(DSL.name("option_values"), SQLDataType.JSONB)

    fun keysOf(selected: List<SelectedOption>): Array<String> = selected.map { it.key }.toTypedArray()

    /** `option_values` as an object of the typed options' values, or null when none is typed. */
    fun valuesOf(selected: List<SelectedOption>): Field<JSONB> =
        selected
            .filter { it.valueJson != null }
            .takeIf { it.isNotEmpty() }
            ?.let { typed ->
                DSL.`val`(typed.joinToString(",", "{", "}") { "\"${it.key}\":${it.valueJson}" }).cast(SQLDataType.JSONB)
            } ?: DSL.`val`(null, SQLDataType.JSONB)
}
