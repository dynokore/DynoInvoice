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

    /**
     * TRANSITIONAL (stage 1 of INV-T-3978): the former worker's full-sync access (coordinator ruling INV-Q-3786), kept
     * only until the read-model clear of a full sync has an owner ruling on its (runtime, sys) form; until then
     * [JdbcConsumerReplay.fullSync] still runs on a worker connection. Never targets a runtime login. Removed in stage 2.
     */
    fun fullSyncWorkerAccess(
        schema: String,
        workerRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val worker = SqlIdentifier.require(workerRole, "worker role")
        return """
            grant usage on schema $s to $worker;
            grant select, insert, update on $s.$CONSUMER_OFFSET to $worker;
            grant select on $s.$CONSUMER_REPLAY to $worker;
            grant update (closed_at) on $s.$CONSUMER_REPLAY to $worker;
            ${legacyFullSyncAccess(s, worker)}
        """.trimIndent()
    }

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
     * The consumer's replay state (INV-T-3385; INV-REQ-1205 b4, b6; rulings INV-Q-3599, INV-Q-3644, INV-Q-3665,
     * INV-Q-3669), part of its consumption state, so a full sync never clears it:
     * - [CONSUMER_REPLAY]: one row per requested replay and addressee producer (a replay of a topic with several
     *   catalogue producers has one row per producer under one replay id, owner ruling INV-Q-3788 A), its mode, scope,
     *   the topics whose position it moved to the head, when that producer's ReplayCompleted was applied and when the
     *   incidents were closed; at most one pending (not closed) replay per (consumer, producer) pair (INV-Q-3845);
     * - [CONSUMER_REPLAY_MARKER]: the sources whose marker for a replay the consumer has applied;
     * - [CONSUMER_SOURCE_SEEN]: on an 'every service' pair, the sources the consumer has seen.
     * They hold no tenant data (positions and service names, as [CONSUMER_OFFSET]), so no RLS. The app role writes them
     * in the consumer's transactions; the worker role reads them and records that an incident was closed.
     */
    private fun legacyReplayDdl(
        s: String,
        role: String,
        worker: String,
    ): String = """
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
        grant select, insert, update on $s.$CONSUMER_REPLAY, $s.$CONSUMER_REPLAY_MARKER, $s.$CONSUMER_SOURCE_SEEN
            to $role;
        grant select on $s.$CONSUMER_REPLAY to $worker;
        grant update (closed_at) on $s.$CONSUMER_REPLAY to $worker;
        ${legacyFullSyncAccess(s, worker)}
    """.trimIndent()

    /**
     * The worker's full-sync access (coordinator ruling INV-Q-3786), the [legacyPurgeAccess] shape: `DELETE` on every
     * tenant's [CONSUMER_SEQUENCE] rows through its own policies, with column `SELECT` on the filter columns
     * (`consumer`, `source`, `tenant_id`) only, never `last_value`; `INSERT` of the pending replay; and the outbox
     * writes of the request and its audit entry, which stay under the tenant policy, so the worker bound to the platform
     * tenant writes platform rows only. Never BYPASSRLS; the app role is unchanged.
     */
    private fun legacyFullSyncAccess(
        s: String,
        worker: String,
    ): String = """
        drop policy if exists ${CONSUMER_SEQUENCE}_full_sync_select on $s.$CONSUMER_SEQUENCE;
        drop policy if exists ${CONSUMER_SEQUENCE}_full_sync on $s.$CONSUMER_SEQUENCE;
        create policy ${CONSUMER_SEQUENCE}_full_sync_select on $s.$CONSUMER_SEQUENCE for select to $worker using (true);
        create policy ${CONSUMER_SEQUENCE}_full_sync on $s.$CONSUMER_SEQUENCE for delete to $worker using (true);
        grant select (consumer, source, tenant_id) on $s.$CONSUMER_SEQUENCE to $worker;
        grant delete on $s.$CONSUMER_SEQUENCE to $worker;
        grant insert on $s.$CONSUMER_REPLAY to $worker;
        grant insert on $s.$OUTBOX to $worker;
        grant select, insert, update on $s.$OUTBOX_SEQUENCE to $worker;
    """.trimIndent()

    /**
     * TRANSITIONAL (stage 1 of INV-T-3978, see [fullSyncWorkerAccess]): still targets the former worker role until the
     * read-model clear has an owner ruling on its (runtime, sys) form.
     *
     * The worker access one read-model [table] needs for a full sync's [ReadModelClear] (coordinator ruling
     * INV-Q-3786), for the service's own migration: `DELETE` on every tenant's rows through its own policies, and column
     * `SELECT` only on [filterColumns], the columns its clear statements filter on, never a content column. Idempotent.
     */
    fun readModelClearAccess(
        schema: String,
        table: String,
        workerRole: String,
        filterColumns: List<String>,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val worker = SqlIdentifier.require(workerRole, "worker role")
        val columns = filterColumns.map { SqlIdentifier.require(it, "column") }
        val select =
            if (columns.isEmpty()) {
                ""
            } else {
                "create policy ${t}_full_sync_select on $s.$t for select to $worker using (true);\n" +
                    "grant select (${columns.joinToString(", ")}) on $s.$t to $worker;"
            }
        return """
            |drop policy if exists ${t}_full_sync_select on $s.$t;
            |drop policy if exists ${t}_full_sync on $s.$t;
            |create policy ${t}_full_sync on $s.$t for delete to $worker using (true);
            |grant usage on schema $s to $worker;
            |grant delete on $s.$t to $worker;
            |$select
        """.trimMargin()
    }

    /**
     * Brings a 1.9.0 schema to 1.10.0: the consumer's replay state ([CONSUMER_REPLAY], [CONSUMER_REPLAY_MARKER],
     * [CONSUMER_SOURCE_SEEN]; INV-T-3385). Run once by the service's migration (TPL-12); not needed for a schema
     * created by [ddl], and harmless on one.
     */
    fun upgradeTo1100(
        schema: String,
        appRole: String,
        workerRole: String,
    ): String = legacyReplayDdl(
        SqlIdentifier.require(schema, "schema"),
        SqlIdentifier.require(appRole, "role"),
        SqlIdentifier.require(workerRole, "worker role"),
    )

    /**
     * The worker's retention purge access (INV-T-2998): `DELETE` on published outbox rows and on inbox rows, each
     * through its own policy, and the column `SELECT` the `WHERE` needs. The outbox policies cannot reach an unpublished
     * row: the restrictive one holds even for a worker session that has bound a tenant (the tenant policy is permissive
     * and would otherwise let it delete that tenant's unpublished rows). The cut-off indexes serve the purge batches;
     * they are not tenant-led because the purge is installation-wide. On a large table run them `CONCURRENTLY` (CS-19)
     * in a migration of their own. The worker still cannot read `data` or any other outbox column beyond the claim
     * grant.
     */
    private fun legacyPurgeAccess(
        s: String,
        worker: String,
    ): String = """
        drop policy if exists ${OUTBOX}_purge on $s.$OUTBOX;
        create policy ${OUTBOX}_purge on $s.$OUTBOX for delete to $worker using (published_at is not null);
        drop policy if exists ${OUTBOX}_purge_published on $s.$OUTBOX;
        create policy ${OUTBOX}_purge_published on $s.$OUTBOX as restrictive for delete to $worker
            using (published_at is not null);
        create index if not exists ${OUTBOX}_published_at on $s.$OUTBOX (published_at) where published_at is not null;
        create index if not exists ${INBOX}_received_at on $s.$INBOX (received_at);
        grant delete on $s.$OUTBOX to $worker;
        drop policy if exists ${INBOX}_purge on $s.$INBOX;
        drop policy if exists ${INBOX}_purge_select on $s.$INBOX;
        create policy ${INBOX}_purge_select on $s.$INBOX for select to $worker using (true);
        create policy ${INBOX}_purge on $s.$INBOX for delete to $worker using (true);
        grant select (consumer, event_id, received_at) on $s.$INBOX to $worker;
        grant delete on $s.$INBOX to $worker;
    """.trimIndent()

    /**
     * Dead-letter access (INV-T-2997): the worker role gets `INSERT` (it records the letter, `raw` included, but cannot
     * read it back), `SELECT` on the non-content columns, `UPDATE` of the triage columns and `DELETE` of triaged letters
     * only, each through its own policy; the restrictive `DELETE` policy holds even for a worker session that has
     * bound a tenant. The app role gets `SELECT` under the tenant policy. Idempotent; replaces the 1.0.0 to 1.4.0
     * `FOR ALL` worker policy and the table-wide worker `SELECT`.
     */
    private fun legacyDeadLetterAccess(
        s: String,
        role: String,
        worker: String,
    ): String {
        val t = "$s.$DEAD_LETTER"
        return """
            drop policy if exists ${DEAD_LETTER}_worker on $t;
            drop policy if exists ${DEAD_LETTER}_worker_select on $t;
            drop policy if exists ${DEAD_LETTER}_worker_insert on $t;
            drop policy if exists ${DEAD_LETTER}_worker_update on $t;
            drop policy if exists ${DEAD_LETTER}_purge on $t;
            drop policy if exists ${DEAD_LETTER}_purge_triaged on $t;
            create policy ${DEAD_LETTER}_worker_select on $t for select to $worker using (true);
            create policy ${DEAD_LETTER}_worker_insert on $t for insert to $worker with check (true);
            create policy ${DEAD_LETTER}_worker_update on $t for update to $worker using (true) with check (true);
            create policy ${DEAD_LETTER}_purge on $t for delete to $worker using (resolved_at is not null);
            create policy ${DEAD_LETTER}_purge_triaged on $t as restrictive for delete to $worker
                using (resolved_at is not null and status <> 'OPEN');
            revoke select on $t from $worker;
            grant select (id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note,
                replayed_to) on $t to $worker;
            grant insert on $t to $worker;
            grant update (status, resolved_at, note, replayed_to) on $t to $worker;
            grant delete on $t to $worker;
            grant select on $t to $role;
            create index if not exists ${DEAD_LETTER}_resolved_at on $t (resolved_at) where resolved_at is not null;
        """.trimIndent()
    }

    /**
     * Brings a schema created by 1.0.0 to 1.1.0: the triage columns of [DEAD_LETTER] and the worker's column
     * `UPDATE` on them. Run once by the service's migration (TPL-12); not needed for a schema created by [ddl].
     */
    fun upgradeTo110(
        schema: String,
        workerRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val worker = SqlIdentifier.require(workerRole, "worker role")
        return """
            alter table $s.$DEAD_LETTER
                add column if not exists status text not null default 'OPEN'
                    check (status in ('OPEN', 'REPLAYED', 'DISCARDED')),
                add column if not exists resolved_at timestamptz,
                add column if not exists note text,
                add column if not exists replayed_to text;
            grant update (status, resolved_at, note, replayed_to) on $s.$DEAD_LETTER to $worker;
        """.trimIndent()
    }

    /**
     * Brings a 1.1.0 schema to 1.2.0: [CONSUMER_SEQUENCE] is keyed per tenant, `(consumer, tenant_id, source,
     * subject)`, as the producer's [OUTBOX_SEQUENCE] counter is `(tenant_id, aggregate_key)`. Under 1.1.0's key
     * two tenants' (or a tenant's and the platform's, INV-Q-2927) events with one source and subject collided,
     * and the second upsert failed row-level security, stalling the consumer. Run once by the service's
     * migration (TPL-12); not needed for a schema created by [ddl], and harmless on one.
     */
    fun upgradeTo120(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            alter table $s.$CONSUMER_SEQUENCE
                drop constraint if exists ${CONSUMER_SEQUENCE}_pkey,
                add primary key (consumer, tenant_id, source, subject);
        """.trimIndent()
    }

    /**
     * Brings a 1.2.0 schema to 1.3.0: the worker's retention purge access ([JdbcRetentionPurge]). Run once by the
     * service's migration (TPL-12); not needed for a schema created by [ddl].
     */
    fun upgradeTo130(
        schema: String,
        workerRole: String,
    ): String = legacyPurgeAccess(SqlIdentifier.require(schema, "schema"), SqlIdentifier.require(workerRole, "worker role"))

    /**
     * Brings a 1.3.0 schema (after [upgradeTo130]) to 1.4.0: the delivery-attempt state of the record at each consumer offset
     * (INV-Q-3108, INV-Q-3110): `attempts` (deliveries begun, counted before the handler runs), `infra_failures`,
     * `first_infra_failure_at` and `retry_not_before`. They reset when the record succeeds. Run once by the
     * service's migration (TPL-12) before the 1.4.0 consumer starts; harmless on a schema created by [ddl].
     */
    fun upgradeTo140(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            alter table $s.$CONSUMER_OFFSET
                add column if not exists attempts int not null default 0,
                add column if not exists infra_failures int not null default 0,
                add column if not exists first_infra_failure_at timestamptz,
                add column if not exists retry_not_before timestamptz;
        """.trimIndent()
    }

    /**
     * Brings a 1.4.0 schema (run after [upgradeTo140]) to 1.5.0: the worker role loses `SELECT` on
     * `event_dead_letter.raw`, the app role gains `SELECT` under the tenant policy, and the worker may delete triaged
     * letters ([JdbcRetentionPurge]). Run once by the service's migration (TPL-12); not needed for a schema created by
     * [ddl].
     */
    fun upgradeTo150(
        schema: String,
        appRole: String,
        workerRole: String,
    ): String = legacyDeadLetterAccess(
        SqlIdentifier.require(schema, "schema"),
        SqlIdentifier.require(appRole, "role"),
        SqlIdentifier.require(workerRole, "worker role"),
    )
}
