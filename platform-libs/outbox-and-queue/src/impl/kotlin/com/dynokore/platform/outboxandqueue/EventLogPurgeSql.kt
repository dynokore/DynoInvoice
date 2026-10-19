package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.EventLogSchema.Topic
import com.dynokore.platform.outboxandqueue.EventLogSchema.tableName
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The purge function of [EventLogSchema.functionsDdl] (split out of that object). */
internal object EventLogPurgeSql {
    /**
     * [EventLogSchema.PURGE_BATCH]: reads the retention row and, when it is missing or a value is below 7 days,
     * deletes nothing and
     * raises a warning (no row returned). Otherwise, per topic, deletes up to [EventLogSchema.PURGE_BATCH_SIZE] rows
     * inserted before
     * `now() - <period>` (the content window for a content topic, the pack artefact retention for a topic marked
     * `retained_while_resolvable`, the event-log retention for any other), lowest offsets first and, on a record topic,
     * none above the keeper's recorded position; raises the topic's watermark to the highest offset deleted (never
     * lowers it) in the same transaction; and returns one row per topic.
     */
    fun purge(
        topics: List<Topic>,
        sysRole: String,
        purgeLogin: String,
    ): List<String> =
        listOf(purgeHead()) + topics.map { purgeBranch(it) } + listOf(purgeTail(), purgeHandOver(sysRole, purgeLogin))

    private fun purgeHead(): String =
        """
        drop function if exists ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_BATCH}();
        create function ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_BATCH}()
        returns table (purged_topic text, purged_content boolean, purged_retained boolean, purged_period interval, purged_cutoff timestamptz,
            purged_rows bigint, purged_watermark bigint)
        language plpgsql security definer set search_path = pg_catalog, ${EventLogSchema.SCHEMA}, pg_temp as $$
        declare
            v_content interval;
            v_retention interval;
            v_pack interval;
            v_position bigint;
            v_max bigint;
        begin
            select r.content_window, r.eventlog_retention, r.pack_retention into v_content, v_retention, v_pack
            from ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION} r where r.id;
            if not found or v_content is null or v_retention is null or v_pack is null
                or v_content < interval '7 days' or v_retention < interval '7 days'
                or v_pack < interval '1827 days' then
                raise warning 'event-log retention is missing or below 7 days: nothing is purged (INV-Q-4262)';
                return;
            end if;
        """.trimIndent()

    private fun purgeTail(): String =
        """
        end
        $$;
        """.trimIndent()

    private fun purgeHandOver(
        sysRole: String,
        purgeLogin: String,
    ): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val purge = SqlIdentifier.require(purgeLogin, "purge login")
        return """
            grant create on schema ${EventLogSchema.SCHEMA} to $sys;
            alter function ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_BATCH}() owner to $sys;
            revoke all on function ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_BATCH}() from public;
            grant usage on schema ${EventLogSchema.SCHEMA} to $purge;
            grant execute on function ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_BATCH}() to $purge;
            revoke create on schema ${EventLogSchema.SCHEMA} from $sys;
            """.trimIndent()
    }

    /** A record topic's purge stops at its keeper's recorded position (INV-REQ-1205 b4). */
    private const val READ_RECORD_POSITION =
        "select coalesce(max(p.position), 0) into v_position from ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} p where p.topic_key = purged_topic;"

    /** Any other topic has no position to respect. */
    private const val NO_RECORD_POSITION = "v_position := null;"

    private fun purgeBranch(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val content = if (topic.contentTopic) "true" else "false"
        // Pack period if marked, else content window, else event-log retention (plain ifs, so the SQL guard checks
        // them).
        val unmarked = if (topic.contentTopic) "v_content" else "v_retention"
        val period = if (topic.retainedWhileResolvable) "v_pack" else unmarked
        val position = if (topic.record) READ_RECORD_POSITION else NO_RECORD_POSITION
        return """
            purged_topic := '$topicKey';
            purged_content := $content;
            purged_retained := ${if (topic.retainedWhileResolvable) "true" else "false"};
            purged_period := $period;
            purged_cutoff := now() - $period;
            $position
            with gone as (
                delete from ${EventLogSchema.SCHEMA}.$topicKey t where t.log_offset in (
                    select c.log_offset from ${EventLogSchema.SCHEMA}.$topicKey c
                    where c.inserted_at < purged_cutoff and (v_position is null or c.log_offset <= v_position)
                    order by c.log_offset limit ${EventLogSchema.PURGE_BATCH_SIZE}
                ) returning t.log_offset
            )
            select count(*), max(g.log_offset) into purged_rows, v_max from gone g;
            if purged_rows > 0 then
                insert into ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} as w (topic_key, watermark, updated_at)
                values ('$topicKey', v_max, now())
                on conflict (topic_key) do update
                    set watermark = greatest(w.watermark, excluded.watermark), updated_at = excluded.updated_at;
            end if;
            select coalesce(max(w.watermark), 0) into purged_watermark
            from ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} w where w.topic_key = '$topicKey';
            return next;
            """.trimIndent()
    }
}
