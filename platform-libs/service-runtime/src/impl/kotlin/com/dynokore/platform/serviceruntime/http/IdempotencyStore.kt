package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TenantRlsMigration
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
            |grant select (created_at) on $s.$TABLE to $worker;
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
 * Writes the request's API-15 record in the caller's open transaction, the command's own; the record then commits
 * or rolls back with the command's effect. Called at most once, inside the transaction.
 */
fun interface IdempotencyRecorder {
    fun record(response: StoredResponse)
}

/**
 * The API-15 Idempotency-Key store of the internal operations service-runtime serves (INV-API-3023, INV-API-3025).
 * The key is stored per principal with a fingerprint of the method, path and body, for at least [retention]
 * (24 hours by default, API-15's minimum): a replay with the same fingerprint returns the original status and
 * body, the same key with another fingerprint is [IdempotencyOutcome.DifferentRequest] (422), and a replay while
 * the original is still running is [IdempotencyOutcome.InFlight] (409).
 *
 * In flight is a PostgreSQL session advisory lock on (tenant, principal, key), held on its own connection of [app]
 * while the request runs, so a crashed instance releases it with its connection. The record is written through
 * the [IdempotencyRecorder] inside the command's own transaction (ARC-05, TPL-04, API-15 S2 T1: the job commands
 * call it through `JobCommandJournal`), so the stored response commits or rolls back with the effect. A response
 * that wrote no state (a refusal, a contract violation) is stored on its own afterwards.
 */
class IdempotencyStore(
    private val scope: TenantScope,
    private val app: DataSource,
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
     * with a 5xx status is not stored: the request did not complete, so a retry runs it again.
     */
    fun once(
        tenant: TenantId?,
        principal: String,
        key: UUID,
        fingerprint: String,
        block: (IdempotencyRecorder) -> StoredResponse,
    ): IdempotencyOutcome {
        val owner = tenant ?: TenantId.PLATFORM
        val lockName = "$table/$owner/$principal/$key"
        return app.connection.use { lock ->
            val held =
                lock.prepareStatement("select pg_try_advisory_lock(hashtextextended(?, 0))").use { statement ->
                    statement.setString(1, lockName)
                    statement.executeQuery().use { it.next() && it.getBoolean(1) }
                }
            if (!held) return@use IdempotencyOutcome.InFlight
            try {
                val stored = find(owner, principal, key)
                when {
                    stored == null -> {
                        var recorded = false
                        val recorder =
                            IdempotencyRecorder { response ->
                                check(scope.tenants.current() == owner) { "the command transaction is not the key's tenant" }
                                insert(owner, principal, key, fingerprint, response)
                                recorded = true
                            }
                        val response = block(recorder)
                        if (!recorded && response.status < SERVER_ERROR) {
                            inTenant(owner) { insert(owner, principal, key, fingerprint, response) }
                        }
                        IdempotencyOutcome.Fresh(response)
                    }

                    stored.first == fingerprint -> IdempotencyOutcome.Replay(stored.second)

                    else -> IdempotencyOutcome.DifferentRequest
                }
            } finally {
                lock.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))").use { statement ->
                    statement.setString(1, lockName)
                    statement.execute()
                }
            }
        }
    }

    /** Deletes keys stored more than [retention] ago, across tenants, through the worker role; returns the count. */
    fun purge(): Int {
        val purger = checkNotNull(worker) { "the purge needs the service's worker role" }
        return purger.connection.use { connection ->
            connection.prepareStatement("delete from $table where created_at < ?").use {
                it.setTimestamp(1, Timestamp.from(clock.instant().minus(retention)))
                it.executeUpdate()
            }
        }
    }

    private fun find(
        tenant: TenantId,
        principal: String,
        key: UUID,
    ): Pair<String, StoredResponse>? = inTenant(tenant) {
        scope.connections.require().prepareStatement(
            "select fingerprint, status, body from $table where tenant_id = ? and principal = ? and idempotency_key = ?",
        ).use { statement ->
            statement.setObject(1, tenant.value)
            statement.setString(2, principal)
            statement.setObject(3, key)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to StoredResponse(rows.getInt(2), rows.getString(3)) else null
            }
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
        block: () -> T,
    ): T = scope.bind(tenant) { scope.transactions.inTransaction(block) }

    companion object {
        val DEFAULT_RETENTION: Duration = Duration.ofHours(24)
        private const val SERVER_ERROR = 500
    }
}
