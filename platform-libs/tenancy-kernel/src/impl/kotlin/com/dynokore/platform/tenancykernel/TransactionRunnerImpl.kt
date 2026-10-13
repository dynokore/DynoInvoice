package com.dynokore.platform.tenancykernel

import java.sql.Connection
import javax.sql.DataSource

/**
 * [TransactionRunner] over a plain [DataSource] (CS-19; no ORM/framework
 * assumed — a service's own `-adapters` module supplies whatever pooled
 * `DataSource` it wires, wrapped in [TenantAwareDataSource]).
 *
 * Binds the tenant and the actor of the current binding as the very first statement
 * of the transaction (ARC-21 as revised: `set_config('app.tenant_id', :tenant, true)`
 * and `set_config('app.actor', :actor, true)`, the latter only when the binding
 * carries an actor; INV-Q-3951, INV-Q-3953, INV-Q-3997), local scope — CS-19
 * requires transaction scope, never session scope, so the setting cannot
 * survive past this one transaction even before the connection is returned to
 * the pool). REQUIRED propagation: a call nested inside an outer
 * `inTransaction` joins the outer transaction and connection rather than
 * opening a second one, and is refused with `IllegalStateException` before any
 * statement when its binding (tenant, actor) differs from the outer one's.
 */
class TransactionRunnerImpl
    @JvmOverloads
    constructor(
        private val dataSource: DataSource,
        private val tenants: TenantContext,
        private val label: ConnectionLabel? = null,
    ) : TransactionRunner,
        CurrentConnection {
        /** The open transaction on this thread and the binding (tenant, actor) it was opened under. */
        private class Open(
            val connection: Connection,
            val tenant: TenantId,
            val actor: String?,
        )

        private val open = ThreadLocal<Open>()

        override fun <T> inTransaction(block: () -> T): T {
            val tenant = tenants.current()
            val actor = tenants.currentActor()

            open.get()?.let { outer ->
                // REQUIRED propagation, only under the same binding (INV-Q-3997): never run tenant B's
                // work under tenant A's app.tenant_id, nor one actor's under another's app.actor.
                check(outer.tenant == tenant && outer.actor == actor) {
                    "A nested transaction must run under the open transaction's binding (INV-Q-3997): " +
                        "open under tenant ${outer.tenant} and actor ${outer.actor ?: "(none)"}, " +
                        "nested under tenant $tenant and actor ${actor ?: "(none)"}."
                }
                return block()
            }

            val connection = dataSource.connection
            try {
                connection.autoCommit = false
                bindSettings(connection, tenant, actor)
                open.set(Open(connection, tenant, actor))

                val result =
                    try {
                        block()
                    } catch (failure: Throwable) {
                        connection.rollback()
                        throw failure
                    }
                connection.commit()
                return result
            } finally {
                open.remove()
                connection.close() // TenantAwareDataSource: RESET app.tenant_id and app.actor, then back to the pool
            }
        }

        /**
         * The first statement of the transaction: one `select`, every setting transaction-local (`true`), so each ends
         * at commit or rollback: the tenant, the actor, and the [label]'s `application_name` when it gives one
         * (INV-Q-4487).
         */
        private fun bindSettings(
            connection: Connection,
            tenant: TenantId,
            actor: String?,
        ) {
            val name = label?.of(connection)
            // whole literals only, so the SQL guard traces each one (INV-Q-4036)
            val sql =
                if (actor == null && name == null) {
                    "select set_config('app.tenant_id', ?, true)"
                } else if (actor == null) {
                    "select set_config('app.tenant_id', ?, true), set_config('application_name', ?, true)"
                } else if (name == null) {
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true)"
                } else {
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), set_config('application_name', ?, true)"
                }
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                statement.setString(index++, tenant.toString())
                if (actor != null) statement.setString(index++, actor)
                if (name != null) statement.setString(index, name)
                statement.execute()
            }
        }

        override fun require(): Connection =
            open.get()?.connection
                ?: throw IllegalStateException(
                    "No transaction is open on this thread (ARC-21): repository calls must run inside " +
                        "TransactionRunner.inTransaction.",
                )
    }

/**
 * The transaction-local `application_name` of a transaction on [connection] (coordinator ruling INV-Q-4487): set in the
 * binding statement with `set_config('application_name', ?, true)`, so it ends at commit or rollback; `null` leaves the
 * statement unchanged. service-runtime passes one that names the pool lane the connection holds a permit of.
 */
fun interface ConnectionLabel {
    fun of(connection: Connection): String?
}
