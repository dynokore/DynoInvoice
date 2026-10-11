package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the tables this module owns in a service's own schema (INV-REQ-1211, INV-REQ-1222):
 * the outbox and its per-key sequence on the producer side, the inbox, sequence memory, offsets
 * and dead letters on the consumer side. A service's migration (TPL-12, CS-19) embeds [ddl];
 * the rows carry identifiers and the producer's payload only (CS-34).
 *
 * Tenant tables ([OUTBOX], [OUTBOX_SEQUENCE], [INBOX], [CONSUMER_SEQUENCE]) carry the mandatory
 * `tenant_id` and tenancy-kernel's row-level security ([TenantRlsMigration.ddl]). The relay and the
 * consumer poll across tenants, so the outbox also gets the claim path of ARC 4.3 step 1a: a
 * `SELECT` policy `TO <worker> USING (true)` and a column grant limited to `seq`, `tenant_id` and
 * `published_at`; never `BYPASSRLS`. The worker finds work there and binds each item's tenant
 * before touching its row. [CONSUMER_OFFSET] holds no tenant data (a position per consumer and topic)
 * and has no RLS. [DEAD_LETTER] keeps whole rejected envelopes (content) for triage and replay: it has a
 * nullable `tenant_id` (filled whenever the envelope's tenant parses), forced RLS with the tenant policy,
 * and worker policies that never expose `raw`: the worker role holds `SELECT` on the non-content columns only
 * (ARC 4.3 step 1a), the app role holds `SELECT` under the tenant policy (a tenant session reads its own letters'
 * `raw`), and a row without a tenant is invisible to every tenant session, so its `raw` is read by nobody
 * (INV-T-2997, INV-Q-2962, INV-Q-3130). See [deadLetterAccess].
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

    /** `CREATE TABLE` statements for every table, in [schema], with the grants for [appRole]. */
    fun ddl(
        schema: String,
        appRole: String,
        workerRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val role = SqlIdentifier.require(appRole, "role")
        val worker = SqlIdentifier.require(workerRole, "worker role")
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
            comment on column $s.$OUTBOX.data is 'class: content';
            comment on column $s.$DEAD_LETTER.raw is 'class: content';
            $rls
            create policy ${OUTBOX}_claim on $s.$OUTBOX for select to $worker using (true);
            grant usage on schema $s to $worker;
            grant select (seq, tenant_id, published_at) on $s.$OUTBOX to $worker;
            grant select, insert, update on $s.$CONSUMER_OFFSET to $worker;
            ${deadLetterAccess(s, role, worker)}
            grant select, insert, update on $s.$OUTBOX, $s.$OUTBOX_SEQUENCE, $s.$CONSUMER_SEQUENCE,
                $s.$CONSUMER_OFFSET to $role;
            grant select, insert on $s.$INBOX to $role;
            ${purgeAccess(s, worker)}
            ${replayDdl(s, role, worker)}
        """.trimIndent()
    }

    /**
     * The consumer's replay state (INV-T-3385; INV-REQ-1205 b4, b6; rulings INV-Q-3599, INV-Q-3644, INV-Q-3665,
     * INV-Q-3669), part of its consumption state, so a full sync never clears it:
     * - [CONSUMER_REPLAY]: one row per requested replay and addressee producer (a replay of a topic with several
     *   catalogue producers has one row per producer under one replay id, owner ruling INV-Q-3788 A), its mode, scope,
     *   the topics whose position it moved to the head, when that producer's ReplayCompleted was applied and when the
     *   incidents were closed; at most one pending (not completed) replay per (consumer, producer) pair;
     * - [CONSUMER_REPLAY_MARKER]: the sources whose marker for a replay the consumer has applied;
     * - [CONSUMER_SOURCE_SEEN]: on an 'every service' pair, the sources the consumer has seen.
     * They hold no tenant data (positions and service names, as [CONSUMER_OFFSET]), so no RLS. The app role writes them
     * in the consumer's transactions; the worker role reads them and records that an incident was closed.
     */
    private fun replayDdl(
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
            where completed_at is null;
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
        ${fullSyncAccess(s, worker)}
    """.trimIndent()

    /**
     * The worker's full-sync access (coordinator ruling INV-Q-3786), the [purgeAccess] shape: `DELETE` on every
     * tenant's [CONSUMER_SEQUENCE] rows through its own policies, with column `SELECT` on the filter columns
     * (`consumer`, `source`, `tenant_id`) only, never `last_value`; `INSERT` of the pending replay; and the outbox
     * writes of the request and its audit entry, which stay under the tenant policy, so the worker bound to the platform
     * tenant writes platform rows only. Never BYPASSRLS; the app role is unchanged.
     */
    private fun fullSyncAccess(
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
    ): String = replayDdl(
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
    private fun purgeAccess(
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
    private fun deadLetterAccess(
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
    ): String = purgeAccess(SqlIdentifier.require(schema, "schema"), SqlIdentifier.require(workerRole, "worker role"))

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
    ): String = deadLetterAccess(
        SqlIdentifier.require(schema, "schema"),
        SqlIdentifier.require(appRole, "role"),
        SqlIdentifier.require(workerRole, "worker role"),
    )
}
