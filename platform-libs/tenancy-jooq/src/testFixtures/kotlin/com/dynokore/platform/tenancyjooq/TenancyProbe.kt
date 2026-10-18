package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

/**
 * The one way a test reaches PostgreSQL directly (coordinator ruling INV-Q-4140; owner ruling INV-Q-4196): a
 * listener-free jOOQ DSLContext on a plain connection of the runtime login, for the catalog and RLS probes of TPL-14
 * (`pg_roles`, `has_table_privilege`, policy checks, direct reads with a bound or no tenant).
 *
 * - It refuses a superuser or BYPASSRLS session on every connection it opens, so a probe can never pass vacuously.
 * - Every probe is bound-parameter SQL and is logged at INFO with the calling test.
 * - It is never a service DSLContext: it lives only in tenancy-jooq's test fixtures, which platform-build's guards keep
 *   off every main and runtime classpath, and its API names no JDBC type.
 *
 * A probe outside [inTenant] and [unbound] runs in its own transaction with no tenant bound.
 */
class TenancyProbe internal constructor(
    private val dataSource: DataSource,
) {
    private val session = ThreadLocal<Connection>()

    /** The first column of the first row of [sql] with [binds] as its bind values, or null when there is no row. */
    fun fetchValue(
        sql: String,
        vararg binds: Any?,
    ): Any? =
        probe(sql, binds) { connection ->
            DSL
                .using(connection, SQLDialect.POSTGRES)
                .fetchOptional(sql, *binds)
                .map { it.get(0) }
                .orElse(null)
        }

    /** Every row of [sql] with [binds] as its bind values, each as column name to value, in result order. */
    fun fetchRows(
        sql: String,
        vararg binds: Any?,
    ): List<Map<String, Any?>> =
        probe(sql, binds) { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).fetch(sql, *binds).intoMaps()
        }

    /**
     * Runs [block] in one transaction bound to [tenant] and [actor] the way tenancy-kernel's transaction runner binds
     * it (tenant, actor and the signed binding first, signed through tenancy-kernel's [SignedBinding]); commits when
     * it returns and rolls back when it throws.
     */
    fun <T> inTenant(
        tenant: TenantId,
        actor: String = "tenancy-probe",
        block: TenancyProbe.() -> T,
    ): T =
        transaction { connection ->
            DSL
                .using(connection, SQLDialect.POSTGRES)
                .fetch(
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                        "set_config('app.binding_sig', ?, true)",
                    tenant.toString(),
                    actor,
                    SignedBinding.value(SignedBinding.key(), tenant, actor),
                )
            log.info("probe [{}] bound tenant {} and actor {}", caller(), tenant, actor)
            block()
        }

    /** Runs [block] in one transaction with no tenant and no actor bound; commits when it returns. */
    fun <T> unbound(block: TenancyProbe.() -> T): T = transaction { block() }

    private fun <T> transaction(block: (Connection) -> T): T {
        check(session.get() == null) { "TenancyProbe transactions do not nest" }
        return open().use { connection ->
            connection.autoCommit = false
            session.set(connection)
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                session.remove()
            }
        }
    }

    private fun <T> probe(
        sql: String,
        binds: Array<out Any?>,
        run: (Connection) -> T,
    ): T {
        // the bind values are not logged: a probe may carry a password (CS-24)
        log.info("probe [{}]: {} ({} bind value(s))", caller(), sql, binds.size)
        val open = session.get()
        return if (open != null) run(open) else open().use { run(it) }
    }

    private fun open(): Connection {
        val connection = dataSource.connection
        try {
            val privileged =
                DSL
                    .using(connection, SQLDialect.POSTGRES)
                    .fetchValue("select rolsuper or rolbypassrls from pg_roles where rolname = current_user")
            check(privileged == false) {
                "TenancyProbe refuses a superuser or BYPASSRLS session; connect it as the runtime login (INV-Q-4140)"
            }
        } catch (refused: Throwable) {
            connection.close()
            throw refused
        }
        return connection
    }

    private fun caller(): String =
        StackWalker.getInstance().walk { frames ->
            frames
                .filter { frame ->
                    !frame.className.startsWith(TenancyProbe::class.java.name) &&
                        !frame.className.startsWith("kotlin.") &&
                        !frame.className.startsWith("org.jooq.")
                }.findFirst()
                .map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                .orElse("unknown")
        }

    companion object {
        private val log = LoggerFactory.getLogger(TenancyProbe::class.java)

        /** A probe on the runtime login [user] at [jdbcUrl]; for library tests without a Spring context. */
        fun connect(
            jdbcUrl: String,
            user: String,
            password: String,
        ): TenancyProbe =
            TenancyProbe(
                PGSimpleDataSource().apply {
                    setUrl(jdbcUrl)
                    this.user = user
                    this.password = password
                },
            )

        /** The probe over a service's application DataSource (the runtime login), for the test auto-configuration. */
        internal fun over(dataSource: DataSource): TenancyProbe = TenancyProbe(dataSource)
    }
}
