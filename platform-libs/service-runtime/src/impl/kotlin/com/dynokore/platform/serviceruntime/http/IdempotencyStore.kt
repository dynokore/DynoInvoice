package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** A response as stored for a replay (API-15: the original status and body). */
data class StoredResponse(val status: Int, val body: String)

/** What [IdempotencyStore.once] did. */
sealed interface IdempotencyOutcome {
    /** [block] ran now; its response was stored unless it was a server error. */
    data class Fresh(val response: StoredResponse) : IdempotencyOutcome

    /** The same key and fingerprint completed before: the stored response, nothing run (API-15). */
    data class Replay(val response: StoredResponse) : IdempotencyOutcome

    /** The same key is still in flight (API-15: 409). */
    data object InFlight : IdempotencyOutcome

    /** The same key came with a different method, path or body (API-15: 422). */
    data object DifferentRequest : IdempotencyOutcome
}

/**
 * DDL for the Idempotency-Key store (API-15) in a service's own schema, embedded by its migration (TPL-12).
 * Additive: one new table. One row per (tenant, principal, key) with the request fingerprint and the response.
 * `tenant_id NOT NULL` with tenancy-kernel's forced tenant policy (CS-19); a caller without a tenant (a platform
 * operator) is stored under the reserved platform tenant (INV-REQ-1226 b7). The purge deletes across tenants
 * through [workerRole] with a policy scoped to the columns it needs, never BYPASSRLS (ARC 4.3 step 1a).
 */
object IdempotencySchema {
    const val TABLE = "idempotency_key"
    private val IDENTIFIER = Regex("^[a-z_][a-z0-9_]*$")

    fun ddl(
        schema: String,
        appRole: String,
        workerRole: String,
    ): String {
        val s = identifier(schema)
        val app = identifier(appRole)
        val worker = identifier(workerRole)
        val rls = TenantRlsMigration.ddl(s, TABLE, policyName = "${TABLE}_tenant_isolation")
        return """
            |create table $s.$TABLE (
            |    tenant_id uuid not null,
            |    principal text not null,
            |    idempotency_key uuid not null,
            |    fingerprint text not null,
            |    status integer not null check (status between 100 and 599),
            |    body text not null,
            |    created_at timestamptz not null,
            |    primary key (tenant_id, principal, idempotency_key)
            |);
            |$rls
            |create policy ${TABLE}_worker_select on $s.$TABLE for select to $worker using (true);
            |create policy ${TABLE}_worker_delete on $s.$TABLE for delete to $worker using (true);
            |grant usage on schema $s to $worker;
            |grant select (tenant_id, principal, idempotency_key, created_at) on $s.$TABLE to $worker;
            |grant delete on $s.$TABLE to $worker;
            |grant select, insert on $s.$TABLE to $app;
        """.trimMargin()
    }

    internal fun identifier(text: String): String {
        require(IDENTIFIER.matches(text)) { "not a plain lower-case SQL identifier" }
        return text
    }
}

/**
 * The API-15 side of one idempotent command, used inside the command's own transaction (ARC-05, TPL-04, API-15
 * S2 T1). [begin] takes the in-flight guard and looks the key up; the command calls it first in its transaction,
 * before any effect. [record] writes the request's API-15 record in that transaction, so it commits or rolls back
 * with the command's effect; called at most once, it begins first if the command has not.
 */
fun interface IdempotencyRecorder {
    fun record(response: StoredResponse)

    fun begin() {}
}

/**
 * The API-15 Idempotency-Key store of the internal operations service-runtime serves (INV-API-3023, INV-API-3025).
 * The key is stored per principal with a fingerprint of the method, path and body, for at least [retention]
 * (24 hours by default, API-15's minimum): a replay with the same fingerprint returns the original status and
 * body, the same key with another fingerprint is [IdempotencyOutcome.DifferentRequest] (422), and a replay while
 * the original is still running is [IdempotencyOutcome.InFlight] (409).
 *
 * One app-pool connection per command (coordinator ruling INV-Q-3945, INV-T-3947): the store opens no
 * connection of its own while the command runs. In flight is a PostgreSQL transaction advisory lock
 * (`pg_try_advisory_xact_lock`) on (tenant, principal, key), taken through the [IdempotencyRecorder] as the first
 * step of the command's own transaction, followed there by the key lookup; it is released at commit or rollback,
 * by a crashed instance's lost connection, and also behind a transaction-mode pooler. The command's effect and
 * the record (the job commands write it through `JobCommandJournal`) then commit or roll back together. A
 * response that wrote no state (a refusal, a contract violation) comes back without a transaction: its guard,
 * lookup and record are one transaction of their own under the key's tenant, the command's one transaction.
 * Called inside an open transaction (TPL-04: `tx.inTransaction { idempotency.once { ... } }`), [once] takes the
 * guard and does the lookup in it before it runs the block.
 */
class IdempotencyStore(
    private val scope: TenantScope,
    private val worker: DataSource?,
    schema: String,
    private val clock: Clock,
    val retention: Duration = DEFAULT_RETENTION,
) {
    private val table = "${IdempotencySchema.identifier(schema)}.${IdempotencySchema.TABLE}"

    init {
        require(retention >= DEFAULT_RETENTION) { "API-15 keeps a key for at least 24 hours" }
    }

    /**
     * Runs [block] once per ([tenant] or the platform tenant, [principal], [key]) and [fingerprint]. A response
     * with a 5xx status, or a thrown failure, is not stored: the request did not complete, so a retry runs it
     * again.
     */
    fun once(
        tenant: TenantId?,
        principal: String,
        key: UUID,
        fingerprint: String,
        block: (IdempotencyRecorder) -> StoredResponse,
    ): IdempotencyOutcome {
        val command = Command(tenant ?: TenantId.PLATFORM, principal, key, fingerprint)
        return try {
            // TPL-04: called inside the command's open transaction, the guard and the lookup are its first step,
            // before any effect of [block]; otherwise the command's own transaction begins them (or [record] does)
            val open = transactionOpen()
            if (open) command.begin()
            val response = block(command)
            if (!command.recorded && response.status < SERVER_ERROR) {
                if (open) {
                    // the command's own transaction is still open: record in it, without binding again (INV-Q-4268)
                    command.record(response)
                } else {
                    // a response that wrote no state: guard, lookup and record in one transaction of the key's tenant,
                    // bound to the request's principal (its `sub`, INV-REQ-854 b1)
                    inTenant(command.owner, principal) { command.record(response) }
                }
            }
            IdempotencyOutcome.Fresh(response)
        } catch (settled: Settled) {
            if (settled.command !== command) throw settled
            settled.outcome
        }
    }

    /** Whether a transaction is open on the calling thread (the enclosing command transaction of TPL-04). */
    private fun transactionOpen(): Boolean = try {
        scope.connections.require()
        true
    } catch (_: IllegalStateException) {
        false
    }

    /** One request's guard, lookup and record, each in the transaction open on the calling thread. */
    private inner class Command(
        val owner: TenantId,
        private val principal: String,
        private val key: UUID,
        private val fingerprint: String,
    ) : IdempotencyRecorder {
        var recorded = false
            private set

        /** The transaction connection [begin] ran on; another one means a new transaction that must begin again. */
        private var begunOn: Connection? = null

        override fun begin() {
            val connection = scope.connections.require()
            if (begunOn === connection) return
            check(scope.tenants.current() == owner) { "the command transaction is not the key's tenant" }
            val held =
                connection.prepareStatement("select pg_try_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
                    statement.setString(1, "$table/$owner/$principal/$key")
                    statement.executeQuery().use { it.next() && it.getBoolean(1) }
                }
            if (!held) throw Settled(this, IdempotencyOutcome.InFlight)
            val stored = find(owner, principal, key)
            if (stored != null) {
                val outcome = if (stored.first == fingerprint) IdempotencyOutcome.Replay(stored.second) else IdempotencyOutcome.DifferentRequest
                throw Settled(this, outcome)
            }
            begunOn = connection
        }

        override fun record(response: StoredResponse) {
            begin()
            insert(owner, principal, key, fingerprint, response)
            recorded = true
        }
    }

    /**
     * The key is in flight or already stored: thrown from the command's transaction before any effect, so the
     * transaction rolls back, and [once] answers [outcome].
     */
    private class Settled(
        val command: IdempotencyRecorder,
        val outcome: IdempotencyOutcome,
    ) : RuntimeException(null, null, false, false)

    /**
     * Deletes keys stored more than [retention] ago, across tenants, through the worker role (ARC 4.3 step 1a),
     * in batches of [PURGE_BATCH] so no statement holds many row locks; returns the count.
     */
    fun purge(): Int {
        val purger = checkNotNull(worker) { "the purge needs the service's worker role" }
        val cutoff = Timestamp.from(clock.instant().minus(retention))
        var total = 0
        purger.connection.use { connection ->
            connection.prepareStatement(
                """
                delete from $table where (tenant_id, principal, idempotency_key) in (
                    select tenant_id, principal, idempotency_key from $table where created_at < ? limit $PURGE_BATCH
                )
                """.trimIndent(),
            ).use { statement ->
                do {
                    statement.setTimestamp(1, cutoff)
                    val deleted = statement.executeUpdate()
                    total += deleted
                } while (deleted == PURGE_BATCH)
            }
        }
        return total
    }

    /** Looks the key up in the open transaction of the current thread. */
    private fun find(
        tenant: TenantId,
        principal: String,
        key: UUID,
    ): Pair<String, StoredResponse>? = scope.connections.require().prepareStatement(
        "select fingerprint, status, body from $table where tenant_id = ? and principal = ? and idempotency_key = ?",
    ).use { statement ->
        statement.setObject(1, tenant.value)
        statement.setString(2, principal)
        statement.setObject(3, key)
        statement.executeQuery().use { rows ->
            if (rows.next()) rows.getString(1) to StoredResponse(rows.getInt(2), rows.getString(3)) else null
        }
    }

    /** Inserts the record in the open transaction of the current thread. */
    private fun insert(
        tenant: TenantId,
        principal: String,
        key: UUID,
        fingerprint: String,
        response: StoredResponse,
    ) {
        scope.connections.require().prepareStatement(
            """
            insert into $table (tenant_id, principal, idempotency_key, fingerprint, status, body, created_at)
            values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, tenant.value)
            statement.setString(2, principal)
            statement.setObject(3, key)
            statement.setString(4, fingerprint)
            statement.setInt(5, response.status)
            statement.setString(6, response.body)
            statement.setTimestamp(7, Timestamp.from(clock.instant()))
            statement.executeUpdate()
        }
    }

    private fun <T> inTenant(
        tenant: TenantId,
        actor: String,
        block: () -> T,
    ): T = scope.bind(tenant, actor) { scope.transactions.inTransaction(block) }

    companion object {
        val DEFAULT_RETENTION: Duration = Duration.ofHours(24)
        private const val SERVER_ERROR = 500

        /** Rows deleted per purge statement. */
        const val PURGE_BATCH = 1000
    }
}
