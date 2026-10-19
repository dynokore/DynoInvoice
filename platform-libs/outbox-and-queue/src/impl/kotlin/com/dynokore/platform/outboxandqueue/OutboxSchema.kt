package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.jooq.Name
import org.jooq.impl.DSL

/**
 * DDL for the tables this module owns in a service's own schema (INV-REQ-1211, INV-REQ-1222):
 * the outbox and its per-key sequence on the producer side, the inbox, sequence memory, offsets
 * and dead letters on the consumer side. A service's migration (TPL-12, CS-19), run as `<svc>_migrate`,
 * embeds [ddl]; the rows carry identifiers and the producer's payload only (CS-34).
 *
 * One runtime login per service (INV-REQ-1189 b2, INV-ADR-3968; owner rulings INV-Q-3951, INV-Q-3953):
 * - Tenant tables ([OUTBOX], [OUTBOX_SEQUENCE], [INBOX], [CONSUMER_SEQUENCE], [DEAD_LETTER]) carry the mandatory
 *   `tenant_id` and tenancy-kernel's forced row-level security keyed on the tenant binding of tenancy-kernel
 *   ([TenantRlsMigration.ddl], INV-REQ-854). The runtime login `<svc>_runtime` works on them under that tenant policy
 * only; no permissive
 *   `USING (true)` policy ever targets it.
 * - Every cross-tenant operation targets the NOLOGIN `<svc>_sys` only, and is reached only through objects
 *   `<svc>_sys` owns: the column-limited claim views [OUTBOX_CLAIM_VIEW] and
 *   [DEAD_LETTER_TRIAGE_VIEW], and SECURITY DEFINER functions with static SQL, `SET search_path = pg_catalog,
 *   <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to the runtime login only ([FULL_SYNC_DELETE],
 *   [OUTBOX_PURGE_EXPIRED], [INBOX_PURGE_EXPIRED], [DEAD_LETTER_PURGE_EXPIRED], [DEAD_LETTER_RECORD],
 *   [DEAD_LETTER_RESOLVE]). Each purge function holds its age predicate and refuses a cut-off later than the
 *   retention floor ([Retention.FLOOR]), so a call can delete only expired rows.
 * - [CONSUMER_OFFSET] and the replay state tables hold positions and service names and have no RLS.
 *   a tenant id in `consumer_replay.scope` names the target of an operator's replay request: request metadata, not
 * tenant data (owner answer INV-Q-4817 A)
 *   The runtime login writes them directly. Each carries `COMMENT ON TABLE ... IS 'platform-scoped: no tenant data'`
 *   and is listed by [platformScopedTables] (INV-ADR-3968 item 4a, INV-T-4134), as is [QueueSchema.LANE_RATE_WINDOW].
 * - [DEAD_LETTER] has `tenant_id uuid NOT NULL` (CS-19): a dead letter whose record names no tenant is stored under the
 *   reserved platform tenant by [DEAD_LETTER_RECORD], and reads as a letter without a tenant. A restrictive SELECT
 * policy
 *   `TO <runtime>` (`tenant_id <> platform tenant`) keeps such a letter out of every session of the runtime login,
 *   whatever tenant it is bound to, so its `raw` is never read (coordinator ruling INV-Q-4819; INV-Q-3130); the
 *   sys-owned [DEAD_LETTER_TRIAGE_VIEW] still lists it.
 *
 * The migration role must be a member of `<svc>_sys` (it hands the views and functions to it); `<svc>_sys` holds
 * `CREATE` on the schema only for the moment of that hand-over.
 */
object OutboxSchema {
    const val OUTBOX = "outbox"
    const val OUTBOX_SEQUENCE = "outbox_sequence"
    const val INBOX = "inbox"
    const val CONSUMER_SEQUENCE = "consumer_sequence"
    const val CONSUMER_OFFSET = "consumer_offset"
    const val DEAD_LETTER = "event_dead_letter"
    const val CONSUMER_REPLAY = "consumer_replay"
    const val CONSUMER_REPLAY_MARKER = "consumer_replay_marker"
    const val CONSUMER_SOURCE_SEEN = "consumer_source_seen"

    /**
     * BEFORE UPDATE trigger (and its function) on [OUTBOX]: `published_at` is set once, to database time (INV-Q-4334).
     */
    const val OUTBOX_PUBLISH_ONCE = "outbox_publish_once"

    /** Sys-owned view: the outbox claim columns (`seq`, `tenant_id`, `published_at`) of every tenant's rows. */
    const val OUTBOX_CLAIM_VIEW = "outbox_claim_v"

    /**
     * Sys-owned function `outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)`: the
     * [OUTBOX_CLAIM_VIEW] rows (`seq`, `tenant_id`, `published_at`) with `seq > after_seq`, in `seq` order, at most
     * `page_size`, only unpublished rows when `unpublished_only` (INV-Q-5612). tenancy-jooq reads the view as a
     * single-tenant table, so the relay and the unrelayed-event check read the claim set through this function.
     */
    const val OUTBOX_CLAIM_PAGE = "outbox_claim_page"

    /**
     * Sys-owned function `consumer_offset_lock(p_consumer text, p_topic text) returns bigint` (INV-Q-5873): inserts the
     * offset row `(p_consumer, p_topic, 1)` if missing, then returns its `next_offset` selected `for update`; the row
     * lock
     * stays until the caller's transaction ends. Raises "offset row missing" when the row is not there.
     */
    const val CONSUMER_OFFSET_LOCK = "consumer_offset_lock"

    /**
     * Sys-owned function `consumer_offset_advance(p_consumer text, p_topic text, p_next_offset bigint)`: sets the next
     * offset and resets `attempts`, `infra_failures`, `first_infra_failure_at` and `retry_not_before` of that row.
     */
    const val CONSUMER_OFFSET_ADVANCE = "consumer_offset_advance"

    /**
     * Sys-owned function `consumer_source_seen_record(p_consumer text, p_producer text, p_source text)`: inserts the
     * row, on conflict nothing.
     */
    const val CONSUMER_SOURCE_SEEN_RECORD = "consumer_source_seen_record"

    /**
     * Sys-owned table function `event_dead_letter_triage_page(...)` (INV-Q-5875): the non-content columns of
     * [DEAD_LETTER_TRIAGE_VIEW], every tenant's and the platform tenant's letters, filtered and paged by its arguments.
     */
    const val DEAD_LETTER_TRIAGE_PAGE = "event_dead_letter_triage_page"

    /** Sys-owned function `outbox_claim_pending()`: the count of [OUTBOX_CLAIM_VIEW] rows with `published_at` null. */
    const val OUTBOX_CLAIM_PENDING = "outbox_claim_pending"

    /** Sys-owned view: every dead letter's non-content columns (never `raw`). */
    const val DEAD_LETTER_TRIAGE_VIEW = "event_dead_letter_triage_v"

    /** `full_sync_delete(consumer, source)`: deletes a consumer's docsequence rows of one source across tenants. */
    const val FULL_SYNC_DELETE = "full_sync_delete"

    /** `outbox_purge_expired(cutoff, batch_size)`: deletes published outbox rows older than the cut-off. */
    const val OUTBOX_PURGE_EXPIRED = "outbox_purge_expired"

    /** `inbox_purge_expired(cutoff, batch_size)`: deletes inbox rows older than the cut-off. */
    const val INBOX_PURGE_EXPIRED = "inbox_purge_expired"

    /**
     * `event_dead_letter_purge_expired(cutoff, batch_size)`: deletes triaged dead letters resolved before the cut-off.
     */
    const val DEAD_LETTER_PURGE_EXPIRED = "event_dead_letter_purge_expired"

    /** `event_dead_letter_record(consumer, topic, log_offset, tenant_id, reason, raw)`: records a dead letter. */
    const val DEAD_LETTER_RECORD = "event_dead_letter_record"

    /** `event_dead_letter_resolve(id, status, resolved_at, note, replayed_to)`: resolves an open dead letter. */
    const val DEAD_LETTER_RESOLVE = "event_dead_letter_resolve"

    /** The upper bound a purge function accepts for one batch. */
    const val MAX_PURGE_BATCH = 10_000

    /** The reserved platform tenant (`TenantId.PLATFORM`), under which a dead letter without a tenant is stored. */
    internal const val PLATFORM_TENANT = "ffffffff-ffff-ffff-ffff-ffffffffffff"

    /**
     * The tables of [schema] this module declares `PLATFORM_SCOPED` (INV-ADR-3968 item 4a, INV-T-4134): no tenant
     * column
     * and no tenant data, each with the table comment `platform-scoped: no tenant data` in its DDL. A service's
     * composition root merges them with its own declaration (`inv.tenancy.platform-scoped-tables`).
     */
    fun platformScopedTables(schema: String): List<String> {
        val s = SqlIdentifier.require(schema, "schema")
        return listOf(
            CONSUMER_OFFSET,
            CONSUMER_REPLAY,
            CONSUMER_REPLAY_MARKER,
            CONSUMER_SOURCE_SEEN,
            QueueSchema.LANE_RATE_WINDOW,
        ).map { "$s.$it" }
    }

    /**
     * The tables this module's jOOQ classes of the outbox side use, by `DSL.name(schema, table)`, with their class for
     * tenancy-jooq (INV-T-4133): the tenant tables [OUTBOX], [OUTBOX_SEQUENCE] and [INBOX] (`tenant_id` under forced
     * row-level security), and the two claim functions, which return rows of no tenant table (the sys-owned function
     * reads every tenant, the function is the grant) and so are declared `PLATFORM_SCOPED`.
     */
    fun jooqTables(schema: String): Map<Name, TableClass> {
        val s = SqlIdentifier.require(schema, "schema")
        return mapOf(
            DSL.name(s, OUTBOX) to TableClass.TENANT,
            DSL.name(s, OUTBOX_SEQUENCE) to TableClass.TENANT,
            DSL.name(s, INBOX) to TableClass.TENANT,
            DSL.name(s, OUTBOX_CLAIM_PAGE) to TableClass.PLATFORM_SCOPED,
            DSL.name(s, DEAD_LETTER_TRIAGE_PAGE) to TableClass.PLATFORM_SCOPED,
            DSL.name(s, DEAD_LETTER) to TableClass.TENANT,
            DSL.name(s, CONSUMER_SEQUENCE) to TableClass.TENANT,
            DSL.name(s, CONSUMER_OFFSET) to TableClass.PLATFORM_SCOPED,
            DSL.name(s, CONSUMER_REPLAY) to TableClass.PLATFORM_SCOPED,
            DSL.name(s, CONSUMER_REPLAY_MARKER) to TableClass.PLATFORM_SCOPED,
            DSL.name(s, CONSUMER_SOURCE_SEEN) to TableClass.PLATFORM_SCOPED,
        ) + QueueSchema.jooqTables(s)
    }

    /**
     * `CREATE TABLE` statements for every table in [schema], the grants for [runtimeRole] and the objects of [sysRole].
     *
     * Precondition (coordinator rulings INV-Q-6586, INV-Q-6663): this DDL neither creates `bound_tenant()` and
     * `bound_actor()` nor switches role. From INV-T-4156 on the schema must already hold them, from tenancy-kernel's
     * `BindingKeyMigration.ddl` run once per schema as the sys login before this DDL; who runs it is INV-Q-6580.
     */
    fun ddl(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        // Statement groups joined outside SQL text, so no generated SQL is spliced into a literal (INV-Q-4036).
        return listOf(
            OutboxTablesSql.tables(schema),
            listOf(OUTBOX, OUTBOX_SEQUENCE, INBOX, CONSUMER_SEQUENCE, DEAD_LETTER).joinToString("\n") {
                TenantRlsMigration.ddl(s, it, policyName = "${it}_tenant_isolation")
            },
            OutboxTablesSql.replayTables(schema),
            OutboxTablesSql.publishOnce(schema),
            OutboxAccessSql.runtimeGrants(schema, runtimeRole),
            OutboxAccessSql.sysAccess(schema, sysRole),
            OutboxAccessSql.views(schema, runtimeRole, sysRole),
            OutboxFunctionsSql.claimFunctions(schema, runtimeRole, sysRole),
            OutboxFunctionsSql.consumerFunctions(schema, runtimeRole, sysRole),
            OutboxFunctionsSql.triagePage(schema, runtimeRole, sysRole),
            OutboxPurgeSql.outboxPurge(schema, runtimeRole, sysRole),
            OutboxPurgeSql.inboxPurge(schema, runtimeRole, sysRole),
            OutboxPurgeSql.deadLetterPurge(schema, runtimeRole, sysRole),
            OutboxPurgeSql.fullSyncDelete(schema, runtimeRole, sysRole),
            OutboxPurgeSql.deadLetterRecord(schema, runtimeRole, sysRole),
            OutboxPurgeSql.deadLetterResolve(schema, runtimeRole, sysRole),
        ).joinToString("\n")
    }

    /**
     * The read-model clear of a full sync (coordinator rulings INV-Q-3786, INV-Q-4064), for the consuming service's own
     * migration, once per read-model [table] its consumer builds: creates the SECURITY DEFINER function
     * `<schema>.<table>_full_sync_clear` ([readModelClearFunctionName]), owned by [sysRole], from a fixed template.
     * - With [filterColumn], it takes one `text` argument, converted to that column's type by the server's input
     *   conversion as an untyped literal is (an invalid value fails with that type's SQLSTATE), and deletes, across
     * every tenant, the rows whose
     *   [filterColumn] equals it; a null or blank argument is refused. Without one, it takes none and deletes every
     * row.
     * - Static SQL, `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to
     *   [runtimeRole] only. The table's `DELETE` policy and the column `SELECT` of [filterColumn] target [sysRole]
     * only;
     *   [runtimeRole] gets nothing on the table here.
     * - Every identifier, the composed function name included (63 bytes at most), comes from [SqlIdentifier.require];
     *   a table of the protected evidence-ledger set ([PROTECTED_LEDGERS], coordinator ruling INV-Q-4034) is refused.
     *
     * Repeatable: the policies are replaced and the function is created or replaced.
     */
    fun readModelClearFunction(
        schema: String,
        table: String,
        runtimeRole: String,
        sysRole: String,
        filterColumn: String? = null,
    ): String {
        val t = SqlIdentifier.require(table, "table")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        SqlIdentifier.require(schema, "schema")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        require(
            t !in PROTECTED_LEDGERS,
        ) { "'$t' is a protected evidence ledger (INV-Q-4034); a full sync never clears it" }
        readModelClearFunctionName(t)
        return if (filterColumn == null) {
            OutboxClearSql.clearAll(schema, table, runtimeRole, sysRole)
        } else {
            OutboxClearSql.clearByColumn(schema, table, filterColumn, runtimeRole, sysRole)
        }
    }

    /**
     * The name of [table]'s full-sync clear function, `<table>_full_sync_clear`, as [SqlIdentifier.require] returns it.
     */
    fun readModelClearFunctionName(table: String): String =
        SqlIdentifier.require("${SqlIdentifier.require(table, "table")}$FULL_SYNC_CLEAR_SUFFIX", "clear function name")

    /**
     * The protected evidence-ledger set (coordinator ruling INV-Q-4034): `audit_entry`, `override_grant` and the stores
     * INV-REQ-2570 declares append-only and immutable from the first write. [readModelClearFunction] refuses them.
     */
    val PROTECTED_LEDGERS: Set<String> =
        setOf(
            "audit_entry",
            "override_grant",
            "source_event",
            "evidence_payload",
            "acknowledgement",
            "decision_trace",
            "audit_log",
            "transport_attempt",
            "notification_ledger",
            "filing_fact",
            "readiness_record",
        )

    private const val FULL_SYNC_CLEAR_SUFFIX = "_full_sync_clear"
}
