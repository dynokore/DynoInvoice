package com.dynokore.platform.tenancykernel

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * [TransactionRunner] over a plain [DataSource] (CS-19; no ORM/framework
 * assumed — a service's own `-adapters` module supplies whatever pooled
 * `DataSource` it wires, wrapped in [TenantAwareDataSource]).
 *
 * Binds the tenant and the actor of the current binding as the very first statement
 * of the transaction (ARC-21 as revised: `set_config('app.tenant_id', :tenant, true)`
 * and `set_config('app.actor', :actor, true)`, both always, a binding with no actor is refused; INV-Q-3951, INV-Q-3953, INV-Q-3997), local scope — CS-19
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
        CurrentConnection,
        SqlErrors {
        private companion object {
            const val MAX_CAUSE_DEPTH = 8
        }

        /** The open transaction on this thread and the binding (tenant, actor) it was opened under. */
        private class Open(
            val connection: Connection,
            val tenant: TenantId,
            val actor: String,
        )

        private val open = ThreadLocal<Open>()

        override fun <T> inTransaction(block: () -> T): T {
            val tenant = tenants.current()
            // A foreign TenantContext may answer null, blank or a control character: refuse before any SQL, by the
            // shared actor rule (INV-Q-3997, INV-Q-4614, INV-T-3999).
            val actor: String =
                tenants.currentActor().takeIf { BlankText.isActor(it) } ?: throw IllegalStateException(
                    "No valid actor is bound for tenant $tenant (INV-REQ-854 b1): every transaction carries an actor " +
                        "that is not blank and holds no control character.",
                )

            open.get()?.let { outer ->
                // REQUIRED propagation, only under the same binding (INV-Q-3997): never run tenant B's
                // work under tenant A's app.tenant_id, nor one actor's under another's app.actor.
                check(outer.tenant == tenant && outer.actor == actor) {
                    "A nested transaction must run under the open transaction's binding (INV-Q-3997): " +
                        "open under tenant ${outer.tenant} and actor ${outer.actor}, " +
                        "nested under tenant $tenant and actor $actor."
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

        override fun <T> outsideOpenTransaction(block: () -> T): T {
            val saved = open.get()
            open.remove()
            try {
                return block()
            } finally {
                if (saved != null) open.set(saved) else open.remove()
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
            actor: String,
        ) {
            val name = label?.current()
            // whole literals only, so the SQL guard traces each one (INV-Q-4036)
            val sql =
                if (name == null) {
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true)"
                } else {
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), set_config('application_name', ?, true)"
                }
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                statement.setString(index++, tenant.toString())
                statement.setString(index++, actor)
                if (name != null) statement.setString(index, name)
                statement.execute()
            }
        }

        override fun sqlStateOf(error: Throwable): String? {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                val state = (current as? SQLException)?.sqlState
                if (state != null) return state
                val next = current.cause
                current = if (next === current) null else next
                depth++
            }
            return null
        }

        override fun transactionToken(): Any? = open.get()?.connection

        override fun require(): Connection =
            open.get()?.connection
                ?: throw IllegalStateException(
                    "No transaction is open on this thread (ARC-21): repository calls must run inside " +
                        "TransactionRunner.inTransaction.",
                )
    }

/**
 * The transaction-local `application_name` of a transaction (coordinator ruling INV-Q-4487): set in the binding
 * statement with `set_config('application_name', ?, true)`, so it ends at commit or rollback; `null` leaves the
 * statement unchanged. service-runtime passes one that names the pool lane the calling thread runs in (it reads the
 * lane from its thread binding, so it needs no connection and no JDBC type).
 */
fun interface ConnectionLabel {
    fun current(): String?
}
