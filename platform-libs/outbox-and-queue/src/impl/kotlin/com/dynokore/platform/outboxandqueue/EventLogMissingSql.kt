package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.EventLogSchema.Topic
import com.dynokore.platform.outboxandqueue.EventLogSchema.tableName
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The events-missing function of [EventLogSchema.functionsDdl] (split out of that object). */
internal object EventLogMissingSql {
    /**
     * [EventLogSchema.EVENTS_MISSING]: refuses a malformed topic, a null array or one of more than
     * [EventLogSchema.MAX_EVENTS_MISSING] ids (`22023`);
     * refuses (`42501`) unless `session_user` produces the topic ([EventLogSchema.PRODUCER]); refuses (`55000`)
     * without a retention
     * row of at least 7 days (1827 for the pack column). Otherwise returns one row: the distinct given ids (nulls
     * ignored) not present in the topic, and the topic's retention cut-off, `now()` less its period (the content window
     * for a content topic, the pack artefact retention for a marked topic, the event-log retention for any other), the
     * same cut-off [EventLogSchema.PURGE_BATCH] deletes before. Nothing else of the topic.
     */
    fun eventsMissing(
        topics: List<Topic>,
        sysRole: String,
    ): List<String> {
        val producerNames = topics.flatMap { it.producers }.distinct().sorted()
        val producerLogins = producerNames.map { SqlIdentifier.require(it, "producer login") }
        val grant =
            if (producerLogins.isEmpty()) {
                null
            } else {
                """
                grant execute on function ${EventLogSchema.SCHEMA}.${EventLogSchema.EVENTS_MISSING}(text, text[]) to ${producerLogins.joinToString(
                    ", ",
                )};
                """.trimIndent()
            }
        return listOf(eventsMissingHead()) + topics.map { eventsMissingBranch(it) } +
            listOfNotNull(EventLogFunctionsSql.branchedTail(), eventsMissingHandOver(sysRole), grant)
    }

    private fun eventsMissingHead(): String =
        """
        drop function if exists ${EventLogSchema.SCHEMA}.${EventLogSchema.EVENTS_MISSING}(text, text[]);
        create function ${EventLogSchema.SCHEMA}.${EventLogSchema.EVENTS_MISSING}(p_topic text, p_event_ids text[], out missing text[],
            out cutoff timestamptz)
        language plpgsql stable security definer set search_path = pg_catalog, ${EventLogSchema.SCHEMA}, pg_temp as $$
        declare
            v_key text;
            v_content interval;
            v_retention interval;
            v_pack interval;
        begin
            if p_topic is null or p_topic !~ '^[a-z][a-z0-9.-]*$' or p_event_ids is null
                or coalesce(cardinality(p_event_ids), 0) > ${EventLogSchema.MAX_EVENTS_MISSING} then
                raise exception 'events_missing names a topic and at most ${EventLogSchema.MAX_EVENTS_MISSING} event ids'
                    using errcode = '22023';
            end if;
            v_key := translate(p_topic, '.-', '__');
            if not exists (
                select 1 from ${EventLogSchema.SCHEMA}.${EventLogSchema.PRODUCER} p where p.topic_key = v_key and p.login = session_user
            ) then
                raise exception 'events_missing is open to the producers of a topic only' using errcode = '42501';
            end if;
            select r.content_window, r.eventlog_retention, r.pack_retention into v_content, v_retention, v_pack
            from ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION} r where r.id;
            if not found or v_content is null or v_retention is null or v_pack is null
                or v_content < interval '7 days' or v_retention < interval '7 days'
                or v_pack < interval '1827 days' then
                raise exception 'event-log retention is missing or below 7 days (INV-Q-4262)' using errcode = '55000';
            end if;
            if false then
                return;
        """.trimIndent()

    private fun eventsMissingHandOver(sysRole: String): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant create on schema ${EventLogSchema.SCHEMA} to $sys;
            alter function ${EventLogSchema.SCHEMA}.${EventLogSchema.EVENTS_MISSING}(text, text[]) owner to $sys;
            revoke all on function ${EventLogSchema.SCHEMA}.${EventLogSchema.EVENTS_MISSING}(text, text[]) from public;
            revoke create on schema ${EventLogSchema.SCHEMA} from $sys;
            """.trimIndent()
    }

    private fun eventsMissingBranch(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        // Pack period if marked, else content window, else event-log retention (plain ifs, so the SQL guard checks
        // them).
        val unmarked = if (topic.contentTopic) "v_content" else "v_retention"
        val period = if (topic.retainedWhileResolvable) "v_pack" else unmarked
        return """
            elsif v_key = '$topicKey' then
                cutoff := now() - $period;
                missing := array(
                    select distinct i.id from unnest(p_event_ids) as i(id)
                    where i.id is not null
                        and not exists (select 1 from ${EventLogSchema.SCHEMA}.$topicKey t where t.event_id = i.id)
                    order by i.id
                );
            """.trimIndent()
    }
}
