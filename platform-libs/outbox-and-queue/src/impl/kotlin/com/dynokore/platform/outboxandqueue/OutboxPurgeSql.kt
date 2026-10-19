package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The purge, full-sync, dead-letter record and resolve functions of [OutboxSchema.ddl] (split out of that object). */
internal object OutboxPurgeSql {
    const val CONTENT_FLOOR = "7 days"

    const val INBOX_FLOOR = "8 days"

    fun outboxPurge(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${OutboxSchema.OUTBOX_PURGE_EXPIRED}(cutoff timestamptz, batch_size integer) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if cutoff is null or cutoff > now() - interval '$CONTENT_FLOOR' then
                    raise exception 'cut-off % is later than the retention floor of $CONTENT_FLOOR', cutoff
                        using errcode = '22023';
                end if;
                if batch_size is null or batch_size < 1 or batch_size > ${OutboxSchema.MAX_PURGE_BATCH} then
                    raise exception 'batch size % is outside 1..${OutboxSchema.MAX_PURGE_BATCH}', batch_size using errcode = '22023';
                end if;
                delete from $s.${OutboxSchema.OUTBOX} where seq in
                    (select seq from $s.${OutboxSchema.OUTBOX} where published_at is not null and published_at < cutoff
                     limit batch_size);
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.OUTBOX_PURGE_EXPIRED}(timestamptz, integer) owner to $sys;
            revoke all on function $s.${OutboxSchema.OUTBOX_PURGE_EXPIRED}(timestamptz, integer) from public;
            grant execute on function $s.${OutboxSchema.OUTBOX_PURGE_EXPIRED}(timestamptz, integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    fun inboxPurge(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${OutboxSchema.INBOX_PURGE_EXPIRED}(cutoff timestamptz, batch_size integer) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if cutoff is null or cutoff > now() - interval '$INBOX_FLOOR' then
                    raise exception 'cut-off % is later than the retention floor of $INBOX_FLOOR', cutoff
                        using errcode = '22023';
                end if;
                if batch_size is null or batch_size < 1 or batch_size > ${OutboxSchema.MAX_PURGE_BATCH} then
                    raise exception 'batch size % is outside 1..${OutboxSchema.MAX_PURGE_BATCH}', batch_size using errcode = '22023';
                end if;
                delete from $s.${OutboxSchema.INBOX} where (consumer, event_id) in
                    (select consumer, event_id from $s.${OutboxSchema.INBOX} where received_at < cutoff limit batch_size);
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.INBOX_PURGE_EXPIRED}(timestamptz, integer) owner to $sys;
            revoke all on function $s.${OutboxSchema.INBOX_PURGE_EXPIRED}(timestamptz, integer) from public;
            grant execute on function $s.${OutboxSchema.INBOX_PURGE_EXPIRED}(timestamptz, integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    fun deadLetterPurge(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${OutboxSchema.DEAD_LETTER_PURGE_EXPIRED}(cutoff timestamptz, batch_size integer) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if cutoff is null or cutoff > now() - interval '$CONTENT_FLOOR' then
                    raise exception 'cut-off % is later than the retention floor of $CONTENT_FLOOR', cutoff
                        using errcode = '22023';
                end if;
                if batch_size is null or batch_size < 1 or batch_size > ${OutboxSchema.MAX_PURGE_BATCH} then
                    raise exception 'batch size % is outside 1..${OutboxSchema.MAX_PURGE_BATCH}', batch_size using errcode = '22023';
                end if;
                delete from $s.${OutboxSchema.DEAD_LETTER} where id in
                    (select id from $s.${OutboxSchema.DEAD_LETTER}
                     where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.DEAD_LETTER_PURGE_EXPIRED}(timestamptz, integer) owner to $sys;
            revoke all on function $s.${OutboxSchema.DEAD_LETTER_PURGE_EXPIRED}(timestamptz, integer) from public;
            grant execute on function $s.${OutboxSchema.DEAD_LETTER_PURGE_EXPIRED}(timestamptz, integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    fun fullSyncDelete(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${OutboxSchema.FULL_SYNC_DELETE}(p_consumer text, p_source text) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                if p_consumer is null or p_consumer ~ '${BlankText.SQL_BLANK}'
                    or p_source is null or p_source ~ '${BlankText.SQL_BLANK}' then
                    raise exception 'a full sync names one consumer and one source' using errcode = '22023';
                end if;
                delete from $s.${OutboxSchema.CONSUMER_SEQUENCE} where consumer = p_consumer and source = p_source;
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.FULL_SYNC_DELETE}(text, text) owner to $sys;
            revoke all on function $s.${OutboxSchema.FULL_SYNC_DELETE}(text, text) from public;
            grant execute on function $s.${OutboxSchema.FULL_SYNC_DELETE}(text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    fun deadLetterRecord(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${OutboxSchema.DEAD_LETTER_RECORD}(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
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
                insert into $s.${OutboxSchema.DEAD_LETTER} (consumer, topic, log_offset, tenant_id, reason, raw)
                values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, '${OutboxSchema.PLATFORM_TENANT}'::uuid), p_reason, p_raw)
                returning id into recorded;
                return recorded;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.DEAD_LETTER_RECORD}(text, text, bigint, uuid, text, text) owner to $sys;
            revoke all on function $s.${OutboxSchema.DEAD_LETTER_RECORD}(text, text, bigint, uuid, text, text) from public;
            grant execute on function $s.${OutboxSchema.DEAD_LETTER_RECORD}(text, text, bigint, uuid, text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    fun deadLetterResolve(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${OutboxSchema.DEAD_LETTER_RESOLVE}(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
                p_replayed_to text) returns boolean
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
                    raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
                end if;
                update $s.${OutboxSchema.DEAD_LETTER} set status = p_status, resolved_at = p_resolved_at, note = p_note,
                    replayed_to = p_replayed_to
                where id = p_id and status = 'OPEN';
                return found;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.DEAD_LETTER_RESOLVE}(bigint, text, timestamptz, text, text) owner to $sys;
            revoke all on function $s.${OutboxSchema.DEAD_LETTER_RESOLVE}(bigint, text, timestamptz, text, text) from public;
            grant execute on function $s.${OutboxSchema.DEAD_LETTER_RESOLVE}(bigint, text, timestamptz, text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }
}
