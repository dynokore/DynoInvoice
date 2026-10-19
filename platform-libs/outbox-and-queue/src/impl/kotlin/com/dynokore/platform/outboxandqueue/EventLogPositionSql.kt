package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.EventLogSchema.Topic
import com.dynokore.platform.outboxandqueue.EventLogSchema.tableName
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The record-position function of [EventLogSchema.functionsDdl] (split out of that object). */
internal object EventLogPositionSql {
    /**
     * [EventLogSchema.RECORD_POSITION_FUNCTION]: refuses a malformed topic and a negative offset; refuses (`42501`)
     * unless the topic
     * has a record-position row whose keeper is `session_user`; moves the position forward only (a lower offset leaves
     * it) and returns the stored position.
     */
    fun recordPosition(
        records: List<Topic>,
        sysRole: String,
    ): List<String> {
        val keeperLogins =
            records.map { checkNotNull(it.keeper) }.distinct().sorted().map {
                SqlIdentifier.require(it, "keeper login")
            }
        val keepers = keeperLogins.joinToString(", ")
        val grant =
            if (keeperLogins.isEmpty()) {
                null
            } else {
                """
                grant execute on function ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION_FUNCTION}(text, bigint) to $keepers;
                """.trimIndent()
            }
        return listOfNotNull(recordPositionBody(), recordPositionHandOver(sysRole), grant)
    }

    private fun recordPositionBody(): String =
        """
        drop function if exists ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION_FUNCTION}(text, bigint);
        create function ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION_FUNCTION}(p_topic text, p_offset bigint) returns bigint
        language plpgsql security definer set search_path = pg_catalog, ${EventLogSchema.SCHEMA}, pg_temp as $$
        declare
            v_key text;
            v_keeper text;
            v_position bigint;
        begin
            if p_topic is null or p_topic !~ '^[a-z][a-z0-9.-]*$' or p_offset is null or p_offset < 0 then
                raise exception 'a record position names a topic and an offset of at least 0' using errcode = '22023';
            end if;
            v_key := translate(p_topic, '.-', '__');
            select r.keeper into v_keeper from ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} r where r.topic_key = v_key for update;
            if not found or v_keeper is distinct from session_user then
                raise exception 'only the keeper of a record topic records its position' using errcode = '42501';
            end if;
            update ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} r set position = greatest(r.position, p_offset), updated_at = now()
            where r.topic_key = v_key
            returning r.position into v_position;
            return v_position;
        end
        $$;
        """.trimIndent()

    private fun recordPositionHandOver(sysRole: String): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant create on schema ${EventLogSchema.SCHEMA} to $sys;
            alter function ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION_FUNCTION}(text, bigint) owner to $sys;
            revoke all on function ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION_FUNCTION}(text, bigint) from public;
            revoke create on schema ${EventLogSchema.SCHEMA} from $sys;
            """.trimIndent()
    }
}
