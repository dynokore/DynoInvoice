package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the tables this module owns in a service's own schema (INV-REQ-1211, INV-REQ-1222):
 * the outbox and its per-key sequence on the producer side, the inbox, sequence memory, offsets
 * and dead letters on the consumer side. A service's migration (TPL-12, CS-19), run as `<svc>_migrate`,
 * embeds [ddl]; the rows carry identifiers and the producer's payload only (CS-34).
 *
 * One runtime login per service (INV-REQ-1189 b2, INV-ADR-3968; owner rulings INV-Q-3951, INV-Q-3953):
 * - Tenant tables ([OUTBOX], [OUTBOX_SEQUENCE], [INBOX], [CONSUMER_SEQUENCE], [DEAD_LETTER]) carry the mandatory
 *   `tenant_id` and tenancy-kernel's forced row-level security keyed on `app.tenant_id` ([TenantRlsMigration.ddl],
 *   INV-REQ-854). The runtime login `<svc>_runtime` works on them under that tenant policy only; no permissive
 *   `USING (true)` policy ever targets it.
 * - Every cross-tenant operation targets the NOLOGIN `<svc>_sys` only, and is reached only through objects
 *   `<svc>_sys` owns ([sysAccess]): the column-limited claim views [OUTBOX_CLAIM_VIEW] and
 *   [DEAD_LETTER_TRIAGE_VIEW], and SECURITY DEFINER functions with static SQL, `SET search_path = pg_catalog,
 *   <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to the runtime login only ([FULL_SYNC_DELETE],
 *   [OUTBOX_PURGE_EXPIRED], [INBOX_PURGE_EXPIRED], [DEAD_LETTER_PURGE_EXPIRED], [DEAD_LETTER_RECORD],
 *   [DEAD_LETTER_RESOLVE]). Each purge function holds its age predicate and refuses a cut-off later than the
 *   retention floor ([Retention.FLOOR]), so a call can delete only expired rows.
 * - [CONSUMER_OFFSET] and the replay state tables hold no tenant data (positions and service names) and have no RLS;
 *   the runtime login writes them directly.
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

    /** Sys-owned view: the outbox claim columns (`seq`, `tenant_id`, `published_at`) of every tenant's rows. */
    const val OUTBOX_CLAIM_VIEW = "outbox_claim_v"

    /** Sys-owned view: every dead letter's non-content columns (never `raw`). */
    const val DEAD_LETTER_TRIAGE_VIEW = "event_dead_letter_triage_v"

    /** `full_sync_delete(consumer, source)`: deletes a consumer's docsequence rows of one source across tenants. */
    const val FULL_SYNC_DELETE = "full_sync_delete"

    /** `outbox_purge_expired(cutoff, batch_size)`: deletes published outbox rows older than the cut-off. */
    const val OUTBOX_PURGE_EXPIRED = "outbox_purge_expired"

    /** `inbox_purge_expired(cutoff, batch_size)`: deletes inbox rows older than the cut-off. */
    const val INBOX_PURGE_EXPIRED = "inbox_purge_expired"

    /** `event_dead_letter_purge_expired(cutoff, batch_size)`: deletes triaged dead letters resolved before the cut-off. */
    const val DEAD_LETTER_PURGE_EXPIRED = "event_dead_letter_purge_expired"

    /** `event_dead_letter_record(consumer, topic, log_offset, tenant_id, reason, raw)`: records a dead letter. */
    const val DEAD_LETTER_RECORD = "event_dead_letter_record"

    /** `event_dead_letter_resolve(id, status, resolved_at, note, replayed_to)`: resolves an open dead letter. */
    const val DEAD_LETTER_RESOLVE = "event_dead_letter_resolve"

    /** The upper bound a purge function accepts for one batch. */
    const val MAX_PURGE_BATCH = 10_000

    /** `CREATE TABLE` statements for every table in [schema], the grants for [runtimeRole] and the objects of [sysRole]. */
    fun ddl(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        val rls = listOf(OUTBOX, OUTBOX_SEQUENCE, INBOX, CONSUMER_SEQUENCE, DEAD_LETTER).joinToString("\n") {
            TenantRlsMigration.ddl(s, it, policyName = "${it}_tenant_isolation")
        }
        return """
            create table $s.$OUTBOX (
                seq bigint generated always as identity primary key,
                id text not null unique,
                topic text not null,
                type text not null,
                source text not null,
                subject text not null,
                event_time timestamptz not null,
                tenant_id uuid not null,
                entity_ref text not null,
                doc_uuid uuid,
                transaction_date date,
                docsequence bigint,
                instance_id text not null,
                correlation_id text not null,
                causation_id text not null,
                data jsonb not null,
                published_at timestamptz
            );
            create index ${OUTBOX}_unpublished on $s.$OUTBOX (seq) where published_at is null;
            create index ${OUTBOX}_published_at on $s.$OUTBOX (published_at) where published_at is not null;
            create table $s.$OUTBOX_SEQUENCE (
                tenant_id uuid not null,
                aggregate_key text not null,
                last_value bigint not null,
                primary key (tenant_id, aggregate_key)
            );
            create table $s.$INBOX (
                consumer text not null,
                event_id text not null,
                tenant_id uuid not null,
                received_at timestamptz not null default now(),
                primary key (consumer, event_id)
            );
            create index ${INBOX}_received_at on $s.$INBOX (received_at);
            create table $s.$CONSUMER_SEQUENCE (
                consumer text not null,
                source text not null,
                subject text not null,
                tenant_id uuid not null,
                last_value bigint not null,
                primary key (consumer, tenant_id, source, subject)
            );
            create table $s.$CONSUMER_OFFSET (
                consumer text not null,
                topic text not null,
                next_offset bigint not null,
                attempts int not null default 0,
                infra_failures int not null default 0,
                first_infra_failure_at timestamptz,
                retry_not_before timestamptz,
                primary key (consumer, topic)
            );
            create table $s.$DEAD_LETTER (
                id bigint generated always as identity primary key,
                consumer text not null,
                topic text not null,
                log_offset bigint not null,
                tenant_id uuid,
                reason text not null,
                raw text not null,
                recorded_at timestamptz not null default now(),
                status text not null default 'OPEN' check (status in ('OPEN', 'REPLAYED', 'DISCARDED')),
                resolved_at timestamptz,
                note text,
                replayed_to text
            );
            create index ${DEAD_LETTER}_resolved_at on $s.$DEAD_LETTER (resolved_at) where resolved_at is not null;
            comment on column $s.$OUTBOX.data is 'class: content';
            comment on column $s.$DEAD_LETTER.raw is 'class: content';
            $rls
            ${replayTables(s)}
            grant usage on schema $s to $runtime;
            grant select, insert, update on $s.$OUTBOX, $s.$OUTBOX_SEQUENCE, $s.$CONSUMER_SEQUENCE,
                $s.$CONSUMER_OFFSET to $runtime;
            grant select, insert on $s.$INBOX to $runtime;
            grant select on $s.$DEAD_LETTER to $runtime;
            grant select, insert, update on $s.$CONSUMER_REPLAY, $s.$CONSUMER_REPLAY_MARKER, $s.$CONSUMER_SOURCE_SEEN
                to $runtime;
            ${sysAccess(s, runtime, sys)}
        """.trimIndent()
    }

    /**
     * The cross-tenant access, all of it held by [sys] and reached by [runtime] only through the views and functions
     * [sys] owns (INV-Q-3953, INV-REQ-1205 b3): `SELECT` and `DELETE` policies `TO <sys>` with column grants limited
     * to what the views and functions read, restrictive policies that keep an unpublished outbox row and an open
     * dead letter out of every delete, then the objects themselves, handed to [sys].
     */
    private fun sysAccess(
        s: String,
        runtime: String,
        sys: String,
    ): String {
        val letters = "$s.$DEAD_LETTER"
        val functions = listOf(
            outboxPurge(s),
            purgeFunction(
                s,
                INBOX_PURGE_EXPIRED,
                INBOX_FLOOR,
                "delete from $s.$INBOX where (consumer, event_id) in " +
                    "(select consumer, event_id from $s.$INBOX where received_at < cutoff limit batch_size)",
            ),
            purgeFunction(
                s,
                DEAD_LETTER_PURGE_EXPIRED,
                CONTENT_FLOOR,
                "delete from $letters where id in " +
                    "(select id from $letters where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff " +
                    "limit batch_size)",
            ),
            fullSyncDelete(s),
            deadLetterRecord(s),
            deadLetterResolve(s),
        )
        return """
            grant usage on schema $s to $sys;
            create policy ${OUTBOX}_sys_select on $s.$OUTBOX for select to $sys using (true);
            create policy ${OUTBOX}_sys_purge on $s.$OUTBOX for delete to $sys using (published_at is not null);
            create policy ${OUTBOX}_sys_purge_published on $s.$OUTBOX as restrictive for delete to $sys
                using (published_at is not null);
            grant select (seq, tenant_id, published_at) on $s.$OUTBOX to $sys;
            grant delete on $s.$OUTBOX to $sys;
            create policy ${INBOX}_sys_select on $s.$INBOX for select to $sys using (true);
            create policy ${INBOX}_sys_purge on $s.$INBOX for delete to $sys using (true);
            grant select (consumer, event_id, received_at) on $s.$INBOX to $sys;
            grant delete on $s.$INBOX to $sys;
            create policy ${CONSUMER_SEQUENCE}_sys_select on $s.$CONSUMER_SEQUENCE for select to $sys using (true);
            create policy ${CONSUMER_SEQUENCE}_sys_full_sync on $s.$CONSUMER_SEQUENCE for delete to $sys using (true);
            grant select (consumer, source, tenant_id) on $s.$CONSUMER_SEQUENCE to $sys;
            grant delete on $s.$CONSUMER_SEQUENCE to $sys;
            create policy ${DEAD_LETTER}_sys_select on $letters for select to $sys using (true);
            create policy ${DEAD_LETTER}_sys_insert on $letters for insert to $sys with check (true);
            create policy ${DEAD_LETTER}_sys_update on $letters for update to $sys using (true) with check (true);
            create policy ${DEAD_LETTER}_sys_purge on $letters for delete to $sys using (resolved_at is not null);
            create policy ${DEAD_LETTER}_sys_purge_triaged on $letters as restrictive for delete to $sys
                using (resolved_at is not null and status <> 'OPEN');
            grant select ($DEAD_LETTER_COLUMNS) on $letters to $sys;
            grant insert on $letters to $sys;
            grant update (status, resolved_at, note, replayed_to) on $letters to $sys;
            grant delete on $letters to $sys;
            create view $s.$OUTBOX_CLAIM_VIEW as select seq, tenant_id, published_at from $s.$OUTBOX;
            create view $s.$DEAD_LETTER_TRIAGE_VIEW as select $DEAD_LETTER_COLUMNS from $letters;
            ${functions.joinToString("\n")}
            ${handOver(s, runtime, sys, views = listOf(OUTBOX_CLAIM_VIEW, DEAD_LETTER_TRIAGE_VIEW), functions = functionSignatures)}
        """.trimIndent()
    }

    private fun outboxPurge(s: String): String = purgeFunction(
        s,
        OUTBOX_PURGE_EXPIRED,
        CONTENT_FLOOR,
        "delete from $s.$OUTBOX where seq in " +
            "(select seq from $s.$OUTBOX where published_at is not null and published_at < cutoff limit batch_size)",
    )

    /**
     * A purge function: refuses a cut-off later than `now() - [floor]` (the retention floor, in SQL) and a batch
     * outside 1..[MAX_PURGE_BATCH], then runs [delete], whose own predicate selects only expired rows.
     */
    private fun purgeFunction(
        s: String,
        name: String,
        floor: String,
        delete: String,
    ): String = """
        create function $s.$name(cutoff timestamptz, batch_size integer) returns bigint
        language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
        declare
            deleted bigint;
        begin
            if cutoff is null or cutoff > now() - interval '$floor' then
                raise exception 'cut-off % is later than the retention floor of $floor', cutoff using errcode = '22023';
            end if;
            if batch_size is null or batch_size < 1 or batch_size > $MAX_PURGE_BATCH then
                raise exception 'batch size % is outside 1..$MAX_PURGE_BATCH', batch_size using errcode = '22023';
            end if;
            $delete;
            get diagnostics deleted = row_count;
            return deleted;
        end
        $$;
    """.trimIndent()

    private fun fullSyncDelete(s: String): String = """
        create function $s.$FULL_SYNC_DELETE(p_consumer text, p_source text) returns bigint
        language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
        declare
            deleted bigint;
        begin
            if p_consumer is null or btrim(p_consumer) = '' or p_source is null or btrim(p_source) = '' then
                raise exception 'a full sync names one consumer and one source' using errcode = '22023';
            end if;
            delete from $s.$CONSUMER_SEQUENCE where consumer = p_consumer and source = p_source;
            get diagnostics deleted = row_count;
            return deleted;
        end
        $$;
    """.trimIndent()

    private fun deadLetterRecord(s: String): String = """
        create function $s.$DEAD_LETTER_RECORD(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
            p_reason text, p_raw text) returns bigint
        language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
        declare
            recorded bigint;
        begin
            if p_consumer is null or btrim(p_consumer) = '' or p_topic is null or p_log_offset is null
                or p_reason is null or p_raw is null then
                raise exception 'a dead letter names its consumer, topic, offset, reason and record' using errcode = '22023';
            end if;
            insert into $s.$DEAD_LETTER (consumer, topic, log_offset, tenant_id, reason, raw)
            values (p_consumer, p_topic, p_log_offset, p_tenant_id, p_reason, p_raw)
            returning id into recorded;
            return recorded;
        end
        $$;
    """.trimIndent()

    private fun deadLetterResolve(s: String): String = """
        create function $s.$DEAD_LETTER_RESOLVE(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
            p_replayed_to text) returns boolean
        language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
        begin
            if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
                raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
            end if;
            update $s.$DEAD_LETTER set status = p_status, resolved_at = p_resolved_at, note = p_note,
                replayed_to = p_replayed_to
            where id = p_id and status = 'OPEN';
            return found;
        end
        $$;
    """.trimIndent()

    /** The signatures of the functions [ddl] creates, for their hand-over and grants. */
    private val functionSignatures = listOf(
        "$OUTBOX_PURGE_EXPIRED(timestamptz, integer)",
        "$INBOX_PURGE_EXPIRED(timestamptz, integer)",
        "$DEAD_LETTER_PURGE_EXPIRED(timestamptz, integer)",
        "$FULL_SYNC_DELETE(text, text)",
        "$DEAD_LETTER_RECORD(text, text, bigint, uuid, text, text)",
        "$DEAD_LETTER_RESOLVE(bigint, text, timestamptz, text, text)",
    )

    /**
     * Hands [views] and [functions] of [s] to [sys] and gives [runtime] `SELECT` on the views and `EXECUTE` on the
     * functions, with nothing for PUBLIC. `ALTER ... OWNER` needs the new owner to hold `CREATE` on the schema, so
     * [sys] holds it for the hand-over only.
     */
    internal fun handOver(
        s: String,
        runtime: String,
        sys: String,
        views: List<String>,
        functions: List<String>,
    ): String = buildString {
        appendLine("grant create on schema $s to $sys;")
        views.forEach {
            appendLine("alter view $s.$it owner to $sys;")
            appendLine("revoke all on $s.$it from public;")
            appendLine("grant select on $s.$it to $runtime;")
        }
        functions.forEach {
            appendLine("alter function $s.$it owner to $sys;")
            appendLine("revoke all on function $s.$it from public;")
            appendLine("grant execute on function $s.$it to $runtime;")
        }
        append("revoke create on schema $s from $sys;")
    }

    private const val DEAD_LETTER_COLUMNS =
        "id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to"
    private const val CONTENT_FLOOR = "7 days"
    private const val INBOX_FLOOR = "8 days"

    private fun replayTables(s: String): String = """
        create table if not exists $s.$CONSUMER_REPLAY (
            replay_id uuid not null,
            consumer text not null,
            producer text not null,
            mode text not null check (mode in ('SCOPE', 'FULL_SYNC')),
            scope text,
            topics text[] not null,
            requested_at timestamptz not null,
            completed_at timestamptz,
            closed_at timestamptz,
            primary key (replay_id, producer)
        );
        create unique index if not exists ${CONSUMER_REPLAY}_pending on $s.$CONSUMER_REPLAY (consumer, producer)
            where closed_at is null;
        create table if not exists $s.$CONSUMER_REPLAY_MARKER (
            replay_id uuid not null,
            source text not null,
            applied_at timestamptz not null default now(),
            primary key (replay_id, source)
        );
        create table if not exists $s.$CONSUMER_SOURCE_SEEN (
            consumer text not null,
            producer text not null,
            source text not null,
            primary key (consumer, producer, source)
        );
    """.trimIndent()

    /**
     * The read-model clear of a full sync (coordinator rulings INV-Q-3786, INV-Q-4064), for the consuming service's own
     * migration, once per read-model [table] its consumer builds: creates the SECURITY DEFINER function
     * `<schema>.<table>_full_sync_clear` ([readModelClearFunctionName]), owned by [sysRole], from a fixed template.
     * - With [filterColumn], it takes one argument of that column's type and deletes, across every tenant, the rows whose
     *   [filterColumn] equals it; a null or blank argument is refused. Without one, it takes none and deletes every row.
     * - Static SQL, `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to
     *   [runtimeRole] only. The table's `DELETE` policy and the column `SELECT` of [filterColumn] target [sysRole] only;
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
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        require(t !in PROTECTED_LEDGERS) { "'$t' is a protected evidence ledger (INV-Q-4034); a full sync never clears it" }
        val name = readModelClearFunctionName(t)
        val column = filterColumn?.let { SqlIdentifier.require(it, "filter column") }
        val deletePolicy = SqlIdentifier.require("${t}_sys_full_sync", "policy name")
        val selectPolicy = SqlIdentifier.require("${t}_sys_full_sync_select", "policy name")
        val function =
            if (column == null) {
                """
                create or replace function $s.$name() returns bigint
                language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
                declare
                    deleted bigint;
                begin
                    delete from $s.$t;
                    get diagnostics deleted = row_count;
                    return deleted;
                end
                $$;
                """.trimIndent()
            } else {
                """
                create or replace function $s.$name(p_value $s.$t.$column%type) returns bigint
                language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
                declare
                    deleted bigint;
                begin
                    if p_value is null or btrim(p_value::text) = '' then
                        raise exception 'the filter value of $s.$t must not be blank' using errcode = '22023';
                    end if;
                    delete from $s.$t where $column = p_value;
                    get diagnostics deleted = row_count;
                    return deleted;
                end
                $$;
                """.trimIndent()
            }
        val select =
            if (column == null) {
                ""
            } else {
                "create policy $selectPolicy on $s.$t for select to $sys using (true);\n" +
                    "grant select ($column) on $s.$t to $sys;\n"
            }
        val signature = if (column == null) "$name()" else "$name($s.$t.$column%type)"
        return "grant usage on schema $s to $sys;\n" +
            "drop policy if exists $selectPolicy on $s.$t;\n" +
            "drop policy if exists $deletePolicy on $s.$t;\n" +
            "create policy $deletePolicy on $s.$t for delete to $sys using (true);\n" +
            "grant delete on $s.$t to $sys;\n" +
            select +
            function + "\n" +
            handOver(s, runtime, sys, views = emptyList(), functions = listOf(signature))
    }

    /** The name of [table]'s full-sync clear function, `<table>_full_sync_clear`, as [SqlIdentifier.require] returns it. */
    fun readModelClearFunctionName(table: String): String = SqlIdentifier.require(SqlIdentifier.require(table, "table") + FULL_SYNC_CLEAR_SUFFIX, "clear function name")

    /**
     * The protected evidence-ledger set (coordinator ruling INV-Q-4034): `audit_entry`, `override_grant` and the stores
     * INV-REQ-2570 declares append-only and immutable from the first write. [readModelClearFunction] refuses them.
     */
    val PROTECTED_LEDGERS: Set<String> = setOf(
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
