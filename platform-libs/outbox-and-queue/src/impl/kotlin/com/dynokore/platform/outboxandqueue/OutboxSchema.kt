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
 *   `tenant_id` and tenancy-kernel's forced row-level security keyed on `app.tenant_id` ([TenantRlsMigration.ddl],
 *   INV-REQ-854). The runtime login `<svc>_runtime` works on them under that tenant policy only; no permissive
 *   `USING (true)` policy ever targets it.
 * - Every cross-tenant operation targets the NOLOGIN `<svc>_sys` only, and is reached only through objects
 *   `<svc>_sys` owns: the column-limited claim views [OUTBOX_CLAIM_VIEW] and
 *   [DEAD_LETTER_TRIAGE_VIEW], and SECURITY DEFINER functions with static SQL, `SET search_path = pg_catalog,
 *   <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to the runtime login only ([FULL_SYNC_DELETE],
 *   [OUTBOX_PURGE_EXPIRED], [INBOX_PURGE_EXPIRED], [DEAD_LETTER_PURGE_EXPIRED], [DEAD_LETTER_RECORD],
 *   [DEAD_LETTER_RESOLVE]). Each purge function holds its age predicate and refuses a cut-off later than the
 *   retention floor ([Retention.FLOOR]), so a call can delete only expired rows.
 * - [CONSUMER_OFFSET] and the replay state tables hold positions and service names and have no RLS; a tenant id in
 *   `consumer_replay.scope` names the target of an operator's replay request: request metadata, not tenant data
 *   (owner answer INV-Q-4817 A). The runtime login writes them directly. Each carries `COMMENT ON TABLE ... IS 'platform-scoped: no tenant data'`
 *   and is listed by [platformScopedTables] (INV-ADR-3968 item 4a, INV-T-4134), as is [QueueSchema.LANE_RATE_WINDOW].
 * - [DEAD_LETTER] has `tenant_id uuid NOT NULL` (CS-19): a dead letter whose record names no tenant is stored under the
 *   reserved platform tenant by [DEAD_LETTER_RECORD], and reads as a letter without a tenant. A restrictive SELECT policy
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

    /** BEFORE UPDATE trigger (and its function) on [OUTBOX]: `published_at` is set once, to database time (INV-Q-4334). */
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

    /** `event_dead_letter_purge_expired(cutoff, batch_size)`: deletes triaged dead letters resolved before the cut-off. */
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
     * The tables of [schema] this module declares `PLATFORM_SCOPED` (INV-ADR-3968 item 4a, INV-T-4134): no tenant column
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
        )
    }

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
        // Statement groups joined outside SQL text, so no generated SQL is spliced into a literal (INV-Q-4036).
        return listOf(
            tables(schema),
            listOf(OUTBOX, OUTBOX_SEQUENCE, INBOX, CONSUMER_SEQUENCE, DEAD_LETTER).joinToString("\n") {
                TenantRlsMigration.ddl(s, it, policyName = "${it}_tenant_isolation")
            },
            replayTables(schema),
            publishOnce(schema),
            runtimeGrants(schema, runtimeRole),
            sysAccess(schema, sysRole),
            views(schema, runtimeRole, sysRole),
            claimFunctions(schema, runtimeRole, sysRole),
            outboxPurge(schema, runtimeRole, sysRole),
            inboxPurge(schema, runtimeRole, sysRole),
            deadLetterPurge(schema, runtimeRole, sysRole),
            fullSyncDelete(schema, runtimeRole, sysRole),
            deadLetterRecord(schema, runtimeRole, sysRole),
            deadLetterResolve(schema, runtimeRole, sysRole),
        ).joinToString("\n")
    }

    private fun tables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
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
            comment on table $s.$CONSUMER_OFFSET is 'platform-scoped: no tenant data';
            create table $s.$DEAD_LETTER (
                id bigint generated always as identity primary key,
                consumer text not null,
                topic text not null,
                log_offset bigint not null,
                tenant_id uuid not null,
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
            """.trimIndent()
    }

    /**
     * Outbox UPDATE hardening (owner ruling INV-Q-4334 B): the runtime login holds UPDATE on `published_at` only (no
     * DELETE; purging stays with [OUTBOX_PURGE_EXPIRED]), and [OUTBOX_PUBLISH_ONCE], a BEFORE UPDATE trigger, lets
     * `published_at` change only from NULL, storing `clock_timestamp()` whatever value was passed. Re-setting,
     * clearing or backdating a published row and changing any other column are refused (SQLSTATE 42501), so a written
     * row's content and actor stay as written and no unpublished row is brought into the purge's range. The relay's
     * statement is unchanged. Marking an unrelayed row published at the current time stays possible: the residual
     * risk of INV-ADR-3968's consequences, detected by the ruling's follow-up task.
     */
    private fun publishOnce(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create function $s.$OUTBOX_PUBLISH_ONCE() returns trigger
            language plpgsql set search_path = pg_catalog, $s, pg_temp as $$
            begin
                if (to_jsonb(new) - 'published_at') is distinct from (to_jsonb(old) - 'published_at') then
                    raise exception 'an outbox row is never rewritten: only published_at is set, once'
                        using errcode = '42501';
                end if;
                if old.published_at is not null then
                    raise exception 'outbox row % is published: published_at is never re-set, cleared or backdated',
                        old.seq using errcode = '42501';
                end if;
                if new.published_at is null then
                    raise exception 'published_at changes only from NULL to the database time' using errcode = '42501';
                end if;
                new.published_at := clock_timestamp();
                return new;
            end
            $$;
            revoke all on function $s.$OUTBOX_PUBLISH_ONCE() from public;
            create trigger $OUTBOX_PUBLISH_ONCE before update on $s.$OUTBOX
                for each row execute function $s.$OUTBOX_PUBLISH_ONCE();
            """.trimIndent()
    }

    private fun runtimeGrants(
        schema: String,
        runtimeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        return """
            grant usage on schema $s to $runtime;
            grant select, insert, update (published_at) on $s.$OUTBOX to $runtime;
            grant select, insert, update on $s.$OUTBOX_SEQUENCE, $s.$CONSUMER_SEQUENCE, $s.$CONSUMER_OFFSET to $runtime;
            grant select, insert on $s.$INBOX to $runtime;
            grant select on $s.$DEAD_LETTER to $runtime;
            create policy ${DEAD_LETTER}_no_platform_tenant on $s.$DEAD_LETTER as restrictive for select to $runtime
                using (tenant_id <> '$PLATFORM_TENANT'::uuid);
            grant select, insert, update on $s.$CONSUMER_REPLAY, $s.$CONSUMER_REPLAY_MARKER, $s.$CONSUMER_SOURCE_SEEN
                to $runtime;
            """.trimIndent()
    }

    /**
     * The cross-tenant access, all of it held by the sys role and reached by the runtime login only through the views
     * and functions the sys role owns (INV-Q-3953, INV-REQ-1205 b3): `SELECT` and `DELETE` policies `TO <sys>` with
     * column grants limited to what the views and functions read, and restrictive policies that keep an unpublished
     * outbox row and an open dead letter out of every delete.
     *
     * `INSERT` on [OUTBOX] (coordinator ruling INV-Q-4254 A) is for a sys-owned definer that writes a platform event
     * with the change it records (config-resolver's `write_installation_setting`). No sys policy covers it: the
     * table's tenant policy alone admits the row, so the sys role inserts only under the bound tenant, the platform
     * tenant for a platform event.
     */
    private fun sysAccess(
        schema: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant usage on schema $s to $sys;
            create policy ${OUTBOX}_sys_select on $s.$OUTBOX for select to $sys using (true);
            create policy ${OUTBOX}_sys_purge on $s.$OUTBOX for delete to $sys using (published_at is not null);
            create policy ${OUTBOX}_sys_purge_published on $s.$OUTBOX as restrictive for delete to $sys
                using (published_at is not null);
            grant select (seq, tenant_id, published_at) on $s.$OUTBOX to $sys;
            grant delete on $s.$OUTBOX to $sys;
            grant insert on $s.$OUTBOX to $sys;
            create policy ${INBOX}_sys_select on $s.$INBOX for select to $sys using (true);
            create policy ${INBOX}_sys_purge on $s.$INBOX for delete to $sys using (true);
            grant select (consumer, event_id, received_at) on $s.$INBOX to $sys;
            grant delete on $s.$INBOX to $sys;
            create policy ${CONSUMER_SEQUENCE}_sys_select on $s.$CONSUMER_SEQUENCE for select to $sys using (true);
            create policy ${CONSUMER_SEQUENCE}_sys_full_sync on $s.$CONSUMER_SEQUENCE for delete to $sys using (true);
            grant select (consumer, source, tenant_id) on $s.$CONSUMER_SEQUENCE to $sys;
            grant delete on $s.$CONSUMER_SEQUENCE to $sys;
            create policy ${DEAD_LETTER}_sys_select on $s.$DEAD_LETTER for select to $sys using (true);
            create policy ${DEAD_LETTER}_sys_insert on $s.$DEAD_LETTER for insert to $sys with check (true);
            create policy ${DEAD_LETTER}_sys_update on $s.$DEAD_LETTER for update to $sys using (true) with check (true);
            create policy ${DEAD_LETTER}_sys_purge on $s.$DEAD_LETTER for delete to $sys using (resolved_at is not null);
            create policy ${DEAD_LETTER}_sys_purge_triaged on $s.$DEAD_LETTER as restrictive for delete to $sys
                using (resolved_at is not null and status <> 'OPEN');
            grant select ($DEAD_LETTER_COLUMNS) on $s.$DEAD_LETTER to $sys;
            grant insert on $s.$DEAD_LETTER to $sys;
            grant update (status, resolved_at, note, replayed_to) on $s.$DEAD_LETTER to $sys;
            grant delete on $s.$DEAD_LETTER to $sys;
            """.trimIndent()
    }

    /**
     * The two claim views, handed to the sys role, `SELECT` for the runtime login only. `ALTER ... OWNER` needs the new
     * owner to hold `CREATE` on the schema, so the sys role holds it for the hand-over only (as in every function
     * below).
     */
    private fun views(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create view $s.$OUTBOX_CLAIM_VIEW as select seq, tenant_id, published_at from $s.$OUTBOX;
            create view $s.$DEAD_LETTER_TRIAGE_VIEW as select $DEAD_LETTER_COLUMNS from $s.$DEAD_LETTER;
            grant create on schema $s to $sys;
            alter view $s.$OUTBOX_CLAIM_VIEW owner to $sys;
            revoke all on $s.$OUTBOX_CLAIM_VIEW from public;
            grant select on $s.$OUTBOX_CLAIM_VIEW to $runtime;
            alter view $s.$DEAD_LETTER_TRIAGE_VIEW owner to $sys;
            revoke all on $s.$DEAD_LETTER_TRIAGE_VIEW from public;
            grant select on $s.$DEAD_LETTER_TRIAGE_VIEW to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /**
     * The two claim-read functions (INV-Q-5612, INV-T-3978's pattern): static SQL, `SET search_path = pg_catalog,
     * <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to the runtime login only, owner the sys role. They
     * read [OUTBOX_CLAIM_VIEW]'s rows, which the sys role owns.
     */
    private fun claimFunctions(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$OUTBOX_CLAIM_PAGE(after_seq bigint, page_size integer, unpublished_only boolean)
            returns table (seq bigint, tenant_id uuid, published_at timestamptz)
            language plpgsql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                return query select v.seq, v.tenant_id, v.published_at from $s.$OUTBOX_CLAIM_VIEW v
                    where v.seq > after_seq and (not unpublished_only or v.published_at is null)
                    order by v.seq limit page_size;
            end
            $$;
            create function $s.$OUTBOX_CLAIM_PENDING() returns bigint
            language plpgsql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                return (select count(*) from $s.$OUTBOX_CLAIM_VIEW v where v.published_at is null);
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$OUTBOX_CLAIM_PAGE(bigint, integer, boolean) owner to $sys;
            revoke all on function $s.$OUTBOX_CLAIM_PAGE(bigint, integer, boolean) from public;
            grant execute on function $s.$OUTBOX_CLAIM_PAGE(bigint, integer, boolean) to $runtime;
            alter function $s.$OUTBOX_CLAIM_PENDING() owner to $sys;
            revoke all on function $s.$OUTBOX_CLAIM_PENDING() from public;
            grant execute on function $s.$OUTBOX_CLAIM_PENDING() to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /*
     * The purge functions: each refuses a cut-off later than `now() - <floor>` (the retention floor) and a batch outside
     * 1..[MAX_PURGE_BATCH], then deletes with its own predicate, which selects only expired rows. Each function is
     * written out in full and handed to the sys role in the same statement group, so every definer's owner is visible
     * where it is created (INV-ADR-3968 item 6).
     */

    private fun outboxPurge(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$OUTBOX_PURGE_EXPIRED(cutoff timestamptz, batch_size integer) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if cutoff is null or cutoff > now() - interval '$CONTENT_FLOOR' then
                    raise exception 'cut-off % is later than the retention floor of $CONTENT_FLOOR', cutoff
                        using errcode = '22023';
                end if;
                if batch_size is null or batch_size < 1 or batch_size > $MAX_PURGE_BATCH then
                    raise exception 'batch size % is outside 1..$MAX_PURGE_BATCH', batch_size using errcode = '22023';
                end if;
                delete from $s.$OUTBOX where seq in
                    (select seq from $s.$OUTBOX where published_at is not null and published_at < cutoff
                     limit batch_size);
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$OUTBOX_PURGE_EXPIRED(timestamptz, integer) owner to $sys;
            revoke all on function $s.$OUTBOX_PURGE_EXPIRED(timestamptz, integer) from public;
            grant execute on function $s.$OUTBOX_PURGE_EXPIRED(timestamptz, integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun inboxPurge(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$INBOX_PURGE_EXPIRED(cutoff timestamptz, batch_size integer) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if cutoff is null or cutoff > now() - interval '$INBOX_FLOOR' then
                    raise exception 'cut-off % is later than the retention floor of $INBOX_FLOOR', cutoff
                        using errcode = '22023';
                end if;
                if batch_size is null or batch_size < 1 or batch_size > $MAX_PURGE_BATCH then
                    raise exception 'batch size % is outside 1..$MAX_PURGE_BATCH', batch_size using errcode = '22023';
                end if;
                delete from $s.$INBOX where (consumer, event_id) in
                    (select consumer, event_id from $s.$INBOX where received_at < cutoff limit batch_size);
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$INBOX_PURGE_EXPIRED(timestamptz, integer) owner to $sys;
            revoke all on function $s.$INBOX_PURGE_EXPIRED(timestamptz, integer) from public;
            grant execute on function $s.$INBOX_PURGE_EXPIRED(timestamptz, integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun deadLetterPurge(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$DEAD_LETTER_PURGE_EXPIRED(cutoff timestamptz, batch_size integer) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if cutoff is null or cutoff > now() - interval '$CONTENT_FLOOR' then
                    raise exception 'cut-off % is later than the retention floor of $CONTENT_FLOOR', cutoff
                        using errcode = '22023';
                end if;
                if batch_size is null or batch_size < 1 or batch_size > $MAX_PURGE_BATCH then
                    raise exception 'batch size % is outside 1..$MAX_PURGE_BATCH', batch_size using errcode = '22023';
                end if;
                delete from $s.$DEAD_LETTER where id in
                    (select id from $s.$DEAD_LETTER
                     where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$DEAD_LETTER_PURGE_EXPIRED(timestamptz, integer) owner to $sys;
            revoke all on function $s.$DEAD_LETTER_PURGE_EXPIRED(timestamptz, integer) from public;
            grant execute on function $s.$DEAD_LETTER_PURGE_EXPIRED(timestamptz, integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun fullSyncDelete(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$FULL_SYNC_DELETE(p_consumer text, p_source text) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if p_consumer is null or p_consumer ~ '${BlankText.SQL_BLANK}'
                    or p_source is null or p_source ~ '${BlankText.SQL_BLANK}' then
                    raise exception 'a full sync names one consumer and one source' using errcode = '22023';
                end if;
                delete from $s.$CONSUMER_SEQUENCE where consumer = p_consumer and source = p_source;
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$FULL_SYNC_DELETE(text, text) owner to $sys;
            revoke all on function $s.$FULL_SYNC_DELETE(text, text) from public;
            grant execute on function $s.$FULL_SYNC_DELETE(text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun deadLetterRecord(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$DEAD_LETTER_RECORD(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
                p_reason text, p_raw text) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                recorded bigint;
            begin
                if p_consumer is null or p_consumer ~ '${BlankText.SQL_BLANK}'
                    or p_topic is null or p_log_offset is null
                    or p_reason is null or p_raw is null then
                    raise exception 'a dead letter names its consumer, topic, offset, reason and record'
                        using errcode = '22023';
                end if;
                insert into $s.$DEAD_LETTER (consumer, topic, log_offset, tenant_id, reason, raw)
                values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, '$PLATFORM_TENANT'::uuid), p_reason, p_raw)
                returning id into recorded;
                return recorded;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$DEAD_LETTER_RECORD(text, text, bigint, uuid, text, text) owner to $sys;
            revoke all on function $s.$DEAD_LETTER_RECORD(text, text, bigint, uuid, text, text) from public;
            grant execute on function $s.$DEAD_LETTER_RECORD(text, text, bigint, uuid, text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun deadLetterResolve(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
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
            grant create on schema $s to $sys;
            alter function $s.$DEAD_LETTER_RESOLVE(bigint, text, timestamptz, text, text) owner to $sys;
            revoke all on function $s.$DEAD_LETTER_RESOLVE(bigint, text, timestamptz, text, text) from public;
            grant execute on function $s.$DEAD_LETTER_RESOLVE(bigint, text, timestamptz, text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private const val DEAD_LETTER_COLUMNS =
        "id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to"
    private const val CONTENT_FLOOR = "7 days"
    private const val INBOX_FLOOR = "8 days"

    private fun replayTables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
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
            comment on table $s.$CONSUMER_REPLAY is 'platform-scoped: no tenant data';
            create unique index if not exists ${CONSUMER_REPLAY}_pending on $s.$CONSUMER_REPLAY (consumer, producer)
                where closed_at is null;
            create table if not exists $s.$CONSUMER_REPLAY_MARKER (
                replay_id uuid not null,
                source text not null,
                applied_at timestamptz not null default now(),
                primary key (replay_id, source)
            );
            comment on table $s.$CONSUMER_REPLAY_MARKER is 'platform-scoped: no tenant data';
            create table if not exists $s.$CONSUMER_SOURCE_SEEN (
                consumer text not null,
                producer text not null,
                source text not null,
                primary key (consumer, producer, source)
            );
            comment on table $s.$CONSUMER_SOURCE_SEEN is 'platform-scoped: no tenant data';
            """.trimIndent()
    }

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
            clearAll(schema, table, runtimeRole, sysRole)
        } else {
            clearByColumn(schema, table, filterColumn, runtimeRole, sysRole)
        }
    }

    private fun clearAll(
        schema: String,
        table: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val name = SqlIdentifier.require("${t}_full_sync_clear", "clear function name")
        val selectPolicy = SqlIdentifier.require("${t}_sys_full_sync_select", "policy name")
        val deletePolicy = SqlIdentifier.require("${t}_sys_full_sync", "policy name")
        return """
            grant usage on schema $s to $sys;
            drop policy if exists $selectPolicy on $s.$t;
            drop policy if exists $deletePolicy on $s.$t;
            create policy $deletePolicy on $s.$t for delete to $sys using (true);
            grant delete on $s.$t to $sys;
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
            grant create on schema $s to $sys;
            alter function $s.$name() owner to $sys;
            revoke all on function $s.$name() from public;
            grant execute on function $s.$name() to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun clearByColumn(
        schema: String,
        table: String,
        filterColumn: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val column = SqlIdentifier.require(filterColumn, "filter column")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val name = SqlIdentifier.require("${t}_full_sync_clear", "clear function name")
        val selectPolicy = SqlIdentifier.require("${t}_sys_full_sync_select", "policy name")
        val deletePolicy = SqlIdentifier.require("${t}_sys_full_sync", "policy name")
        return """
            grant usage on schema $s to $sys;
            drop policy if exists $selectPolicy on $s.$t;
            drop policy if exists $deletePolicy on $s.$t;
            create policy $deletePolicy on $s.$t for delete to $sys using (true);
            grant delete on $s.$t to $sys;
            create policy $selectPolicy on $s.$t for select to $sys using (true);
            grant select ($column) on $s.$t to $sys;
            create or replace function $s.$name(p_value $s.$t.$column%type) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if p_value is null or p_value::text ~ '${BlankText.SQL_BLANK}' then
                    raise exception 'the filter value of $s.$t must not be blank' using errcode = '22023';
                end if;
                delete from $s.$t where $column = p_value;
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$name($s.$t.$column%type) owner to $sys;
            revoke all on function $s.$name($s.$t.$column%type) from public;
            grant execute on function $s.$name($s.$t.$column%type) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /** The name of [table]'s full-sync clear function, `<table>_full_sync_clear`, as [SqlIdentifier.require] returns it. */
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
