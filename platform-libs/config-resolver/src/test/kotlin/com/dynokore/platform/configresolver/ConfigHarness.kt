package com.dynokore.platform.configresolver

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Testcontainers needs a concrete subclass for its fluent `self()` typing. */
private class Postgres(
    image: DockerImageName,
) : PostgreSQLContainer<Postgres>(image)

/**
 * One PostgreSQL container (16, 17 or 18) holding this module's tables in a fixture service schema `svc`,
 * created by [ConfigSchema.ddl] after the outbox it writes into, as the service's migration login would. Settings are
 * written by [setTenant] as the runtime login inserts them and by [setInstallation] as a row the installation write
 * function would store (the setting operation itself is [JdbcPolicyWriter]'s, tested by the write-path tests).
 */
class ConfigHarness(
    image: String,
    form: DeploymentForm = DeploymentForm.SAAS,
) : AutoCloseable {
    private val container = Postgres(DockerImageName.parse(image)).withInitScript("db/config-resolver-test-roles.sql")

    init {
        container.start()
        dataSource("config_owner", "config_owner_pw").connection.use { c ->
            c.createStatement().use {
                it.execute(OutboxSchema.ddl(SCHEMA, "config_app", "config_sys"))
                it.execute(ConfigSchema.ddl(SCHEMA, "config_app", "config_sys"))
            }
        }
    }

    val tenants = TenantContextImpl()
    val app = TransactionRunnerImpl(TenantAwareDataSource(dataSource("config_app", "config_app_pw")), tenants)
    val admin: DataSource = dataSource(container.username, container.password)
    val reader = JdbcPolicyReader(app, TestCatalogue.catalogue, form, SCHEMA)
    val recorder = JdbcResolutionRecorder(app, SCHEMA, CLOCK)
    val purge = JdbcConfigPurge(app, SCHEMA)

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
                val c = app.require()
                c
                    .prepareStatement(
                        "insert into $SCHEMA.config_setting (id, tenant_id, entity_id, vp_key, scope_level, " +
                            "scope_id, " +
                            "option_keys, option_values, source, profile_id, profile_version, effective_from, " +
                            "set_by, " +
                            "reason, recorded_at) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'user-1', 'test', ?)",
                    ).use {
                        it.setObject(1, id)
                        it.setObject(2, tenant.value)
                        it.setString(3, entity)
                        it.setString(4, key.value)
                        it.setString(5, ref.level.name)
                        it.setString(6, ref.id)
                        bindSelected(it, c, 7, selected)
                        it.setString(9, if (profile == null) "OVERRIDE" else "PROFILE")
                        it.setString(10, profile?.first)
                        it.setString(11, profile?.second)
                        it.setObject(12, from)
                        it.setTimestamp(13, Timestamp.from(CLOCK.instant()))
                        it.executeUpdate()
                    }
                id
            }
        }

    /**
     * Inserts an installation setting as the superuser: the runtime login cannot, and the write function also writes
     * the value's audit entry and PolicyChanged, which resolution does not read.
     */
    fun setInstallation(
        key: VariationPointKey,
        selected: List<SelectedOption>,
        from: LocalDate,
    ): UUID =
        admin.connection.use { c ->
            val id = UUID.randomUUID()
            c
                .prepareStatement(
                    "insert into $SCHEMA.config_installation_setting (id, vp_key, option_keys, option_values, " +
                        "effective_from, set_by, reason, recorded_at) " +
                        "values (?, ?, ?, ?, ?, 'operator-1', 'test', ?)",
                ).use {
                    it.setObject(1, id)
                    it.setString(2, key.value)
                    bindSelected(it, c, 3, selected)
                    it.setObject(5, from)
                    it.setTimestamp(6, Timestamp.from(CLOCK.instant()))
                    it.executeUpdate()
                }
            id
        }

    /** Runs [sql] as [role]'s connection inside a transaction of [tenant]; returns the update count. */
    fun execAs(
        runner: TransactionRunnerImpl,
        tenant: TenantId,
        sql: String,
    ): Int =
        asTenant(tenant) {
            runner.inTransaction { runner.require().createStatement().use { it.executeUpdate(sql) } }
        }

    fun count(sql: String): Long =
        admin.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** `option_keys` at [index], `option_values` (an object of typed values, or null) at [index] + 1. */
    private fun bindSelected(
        statement: PreparedStatement,
        connection: Connection,
        index: Int,
        selected: List<SelectedOption>,
    ) {
        statement.setArray(index, connection.createArrayOf("text", selected.map { it.key }.toTypedArray()))
        val typed = selected.filter { it.valueJson != null }
        if (typed.isEmpty()) {
            statement.setNull(index + 1, Types.OTHER)
        } else {
            val json = typed.joinToString(",", "{", "}") { "\"${it.key}\":${it.valueJson}" }
            statement.setObject(index + 1, json, Types.OTHER)
        }
    }

    override fun close() {
        container.stop()
    }

    private fun dataSource(
        user: String,
        password: String,
    ): DataSource =
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            this.user = user
            this.password = password
        }

    companion object {
        const val SCHEMA = "svc"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
    }
}
