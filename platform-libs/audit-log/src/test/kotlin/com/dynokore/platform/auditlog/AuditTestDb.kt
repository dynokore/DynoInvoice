package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID
import javax.sql.DataSource

/**
 * The database access of audit-log's integration tests (owner ruling INV-Q-4508 (A)): pools only through the
 * `TestPoolFixture`, rows through a tenancy-jooq `DSLContext` on a `TransactionRunner`, catalog and RLS reads through
 * `TenancyProbe`. The fixture's roles are outbox-and-queue's: the runtime login ([tx]) and the schema owner
 * ([ownerTx], the route for a state change the runtime login has no grant for). The probe refuses a superuser, so a
 * count over several tenants is the sum of one RLS-bound count per tenant.
 */
internal class AuditTestDb(
    val fixture: OutboxPostgresFixture,
    keys: BindingKeySource? = null,
) : AutoCloseable {
    val tenants = TenantContextImpl()
    private val appPool =
        TestPoolFixture(
            fixture.jdbcUrl(),
            OutboxPostgresFixture.APP,
            OutboxPostgresFixture.APP_PASSWORD,
            poolName = "audit-app",
        )
    private val ownerPool =
        TestPoolFixture(
            fixture.jdbcUrl(),
            OutboxPostgresFixture.OWNER,
            OutboxPostgresFixture.OWNER_PASSWORD,
            poolName = "audit-owner",
        )

    /** The runtime login's runner; it reads an SQLSTATE through `sqlStateOf`. */
    val tx: TransactionRunnerImpl = appPool.runner(tenants, keys = keys)

    /** The runtime login's data source, for a runner of the fixture. */
    val appDataSource: DataSource = appPool.dataSource()

    /** The schema owner's runner. */
    val ownerTx: TransactionRunnerImpl = ownerPool.runner(tenants, keys = keys)

    /** The runtime login's runner under a key the schema does not hold: every binding it signs is forged. */
    val forgedTx: TransactionRunnerImpl =
        appPool.runner(
            tenants,
            keys = SignedBinding.keys(SignedBinding.key(FORGED_FILL)),
        )

    /** The runtime login as a probe: a catalog read, or a read of tenant rows under RLS alone. */
    val app: TenancyProbe =
        TenancyProbe.connect(fixture.jdbcUrl(), OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)

    fun dsl(
        runner: TransactionRunner,
        tables: Map<String, TableClass>,
    ): DSLContext =
        TenancyJooq.dslContext(
            tables.mapKeys { (name, _) -> DSL.name(name.substringBefore('.'), name.substringAfter('.')) },
            tenants,
            runner,
        )

    /**
     * Runs [block] on the runtime login in one transaction bound to [tenant] and [actor] that [runner] signs. A
     * non-null [signature] replaces `app.binding_sig` after the runner's first statement (a value signed for another
     * actor, or a forgery), so a test reaches a binding the runner itself never signs. [tables] are the tables
     * [block] touches.
     */
    fun <T> signed(
        tenant: TenantId,
        actor: String,
        runner: TransactionRunner = tx,
        signature: String? = null,
        tables: Map<String, TableClass> = emptyMap(),
        block: DSLContext.() -> T,
    ): T {
        val dsl = dsl(runner, tables)
        val run = {
            runner.inTransaction {
                if (signature != null) {
                    dsl
                        .select(
                            DSL.function(
                                "set_config",
                                String::class.java,
                                DSL.inline("app.binding_sig"),
                                DSL.`val`(signature),
                                DSL.inline(true),
                            ),
                        ).fetch()
                }
                dsl.block()
            }
        }
        return if (tenant.isPlatform) tenants.bindPlatform(actor, run) else tenants.bind(tenant, actor, run)
    }

    /** The sum over [bound] of the one-number [sql], each read under that tenant's RLS binding. */
    fun count(
        sql: String,
        bound: Collection<UUID>,
        vararg args: Any,
    ): Long =
        bound.toSet().sumOf { tenant ->
            app.inTenant(TenantId(tenant), "audit-test") { fetchValue(sql, *args) as Long }
        }

    /** The first column of the first row of [sql], read under the RLS binding of [tenant]; null when no row. */
    fun text(
        sql: String,
        tenant: UUID,
        vararg args: Any,
    ): String? =
        app
            .inTenant(TenantId(tenant), "audit-test") {
                fetchRows(sql, *args)
            }.firstOrNull()
            ?.values
            ?.first()
            ?.toString()

    /**
     * One row of `schema.table` written by the schema owner under [tenant]'s binding, for a state the runtime login has
     * no grant to write (route R4). [values] maps a column to its value; a null value is a text null.
     */
    fun ownerInsert(
        ownerDsl: DSLContext,
        tenant: TenantId,
        schema: String,
        table: String,
        values: Map<String, Any?>,
    ) {
        asOwner(tenant) {
            ownerDsl
                .insertInto(DSL.table(DSL.name(schema, table)))
                .set(
                    values.mapKeys { (column, value) ->
                        DSL.field(
                            DSL.name(column),
                            value?.javaClass ?: String::class.java,
                        )
                    },
                ).execute()
        }
    }

    /** Runs [block] in one transaction of the schema owner, bound to [tenant] (platform binding for the platform). */
    fun <T> asOwner(
        tenant: TenantId,
        block: () -> T,
    ): T =
        if (tenant.isPlatform) {
            tenants.bindPlatform("test-owner") { ownerTx.inTransaction(block) }
        } else {
            tenants.bind(tenant, "test-owner") { ownerTx.inTransaction(block) }
        }

    /** A catalog or other tenant-free read of one number. */
    fun number(
        sql: String,
        vararg args: Any,
    ): Long = app.fetchValue(sql, *args) as Long

    /** The first column of every row of the tenant-free [sql]. */
    fun strings(
        sql: String,
        vararg args: Any,
    ): List<String?> = app.fetchRows(sql, *args).map { it.values.first()?.toString() }

    override fun close() {
        appPool.close()
        ownerPool.close()
        fixture.close()
    }

    companion object {
        private const val FORGED_FILL = 2

        /**
         * Init SQL that gives [schema] the key table and `bound_tenant()` / `bound_actor()` of [BindingKeyMigration]
         * (slice 1 of INV-T-4156), created by [sys] with the key of [SignedBinding.key] ([fill]), to run before the
         * module's own DDL by the superuser of the fixture's init script (`reset role` first).
         */
        fun bindingInit(
            schema: String,
            runtime: String,
            sys: String,
            fill: Int = 1,
        ): String {
            val key = SignedBinding.key(fill).joinToString("") { "%02x".format(it) }
            return "create extension if not exists pgcrypto schema public;\n" +
                "grant usage, create on schema $schema to $sys;\nset role $sys;\n" +
                BindingKeyMigration.ddl(schema, sys, runtime) + "\n" +
                "insert into $schema.binding_key (slot, secret) values ('current', decode('$key', 'hex'));\n" +
                "reset role;\n"
        }
    }
}
