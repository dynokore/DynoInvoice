package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.EventLogSchema.Topic
import com.dynokore.platform.outboxandqueue.EventLogSchema.tableName
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The claim function of [EventLogSchema.functionsDdl] (split out of that object). */
internal object EventLogFunctionsSql {
    /**
     * [EventLogSchema.CLAIM]: refuses a malformed topic, a negative offset and a limit outside
     * 1..[EventLogSchema.MAX_CLAIM]; returns nothing unless
     * `session_user` subscribes to the topic ([EventLogSchema.SUBSCRIPTION]); then the claim columns of the topic's
     * branch. STABLE, so
     * a caller reading its watermark in the same statement reads it in the same snapshot.
     */
    fun claim(
        topics: List<Topic>,
        sysRole: String,
    ): List<String> {
        val subscriberNames = topics.flatMap { it.subscribers }.distinct().sorted()
        val subscriberLogins = subscriberNames.map { SqlIdentifier.require(it, "subscriber login") }
        val grant =
            if (subscriberLogins.isEmpty()) {
                null
            } else {
                """
                grant execute on function ${EventLogSchema.SCHEMA}.${EventLogSchema.CLAIM}(text, bigint, integer) to ${subscriberLogins.joinToString(
                    ", ",
                )};
                """.trimIndent()
            }
        return listOf(claimHead()) + topics.map { claimBranch(it) } +
            listOfNotNull(branchedTail(), claimHandOver(sysRole), grant)
    }

    private fun claimHead(): String =
        """
        drop function if exists ${EventLogSchema.SCHEMA}.${EventLogSchema.CLAIM}(text, bigint, integer);
        create function ${EventLogSchema.SCHEMA}.${EventLogSchema.CLAIM}(p_topic text, p_after_offset bigint, p_limit integer)
        returns table (log_offset bigint, tenant_id uuid, inserted_at timestamptz)
        language plpgsql stable security definer set search_path = pg_catalog, ${EventLogSchema.SCHEMA}, pg_temp as $$
        declare
            v_key text;
        begin
            if p_topic is null or p_topic !~ '^[a-z][a-z0-9.-]*$' or p_after_offset is null or p_after_offset < 0
                or p_limit is null or p_limit < 1 or p_limit > ${EventLogSchema.MAX_CLAIM} then
                raise exception 'a claim names a topic, an offset of at least 0 and a limit of 1..${EventLogSchema.MAX_CLAIM}'
                    using errcode = '22023';
            end if;
            v_key := translate(p_topic, '.-', '__');
            if not exists (
                select 1 from ${EventLogSchema.SCHEMA}.${EventLogSchema.SUBSCRIPTION} s where s.topic_key = v_key and s.login = session_user
            ) then
                return;
            end if;
            if false then
                return;
        """.trimIndent()

    fun branchedTail(): String =
        """
            end if;
        end
        $$;
        """.trimIndent()

    private fun claimHandOver(sysRole: String): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant create on schema ${EventLogSchema.SCHEMA} to $sys;
            alter function ${EventLogSchema.SCHEMA}.${EventLogSchema.CLAIM}(text, bigint, integer) owner to $sys;
            revoke all on function ${EventLogSchema.SCHEMA}.${EventLogSchema.CLAIM}(text, bigint, integer) from public;
            revoke create on schema ${EventLogSchema.SCHEMA} from $sys;
            """.trimIndent()
    }

    private fun claimBranch(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        return """
            elsif v_key = '$topicKey' then
                return query
                    select t.log_offset, t.tenant_id, t.inserted_at from ${EventLogSchema.SCHEMA}.$topicKey t
                    where t.log_offset > p_after_offset order by t.log_offset limit p_limit;
            """.trimIndent()
    }
}
