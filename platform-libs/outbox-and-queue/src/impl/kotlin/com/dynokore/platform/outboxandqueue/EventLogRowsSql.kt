package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.EventLogSchema.Topic
import com.dynokore.platform.outboxandqueue.EventLogSchema.tableName
import com.dynokore.platform.tenancykernel.SqlIdentifier

/**
 * The tables, grants and row statements of [EventLogSchema.schemaDdl], [EventLogSchema.topicDdl] and
 * [EventLogSchema.functionsDdl] (split out of that object).
 */
internal object EventLogRowsSql {
    fun membershipTables(): String =
        """
        create table ${EventLogSchema.SCHEMA}.${EventLogSchema.SUBSCRIPTION} (
            topic_key text not null,
            login text not null,
            primary key (topic_key, login)
        );
        comment on table ${EventLogSchema.SCHEMA}.${EventLogSchema.SUBSCRIPTION} is 'platform-scoped: no tenant data';
        create table ${EventLogSchema.SCHEMA}.${EventLogSchema.PRODUCER} (
            topic_key text not null,
            login text not null,
            primary key (topic_key, login)
        );
        comment on table ${EventLogSchema.SCHEMA}.${EventLogSchema.PRODUCER} is 'platform-scoped: no tenant data';
        """.trimIndent()

    fun watermarkTable(sysRole: String): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create table ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} (
                topic_key text primary key,
                watermark bigint not null check (watermark >= 0),
                updated_at timestamptz not null default now()
            );
            comment on table ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} is 'platform-scoped: no tenant data';
            alter table ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} enable row level security;
            alter table ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} force row level security;
            create policy sys_purge on ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} for all to $sys using (true) with check (true);
            """.trimIndent()
    }

    fun stateTables(sysRole: String): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create table ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} (
                topic_key text primary key,
                keeper text not null,
                position bigint not null default 0 check (position >= 0),
                updated_at timestamptz not null default now()
            );
            comment on table ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} is 'platform-scoped: no tenant data';
            create table ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION} (
                id boolean primary key default true check (id),
                content_window interval not null check (content_window >= interval '7 days'),
                eventlog_retention interval not null check (eventlog_retention >= interval '7 days'),
                pack_retention interval not null default interval '1827 days'
                    check (pack_retention >= interval '1827 days'),
                updated_at timestamptz not null default now()
            );
            comment on table ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION} is 'platform-scoped: no tenant data';
            revoke all on ${EventLogSchema.SCHEMA}.${EventLogSchema.SUBSCRIPTION}, ${EventLogSchema.SCHEMA}.${EventLogSchema.PRODUCER}, ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK}, ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION}, ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION}
                from public;
            grant usage on schema ${EventLogSchema.SCHEMA} to $sys;
            grant select on ${EventLogSchema.SCHEMA}.${EventLogSchema.SUBSCRIPTION} to $sys;
            grant select on ${EventLogSchema.SCHEMA}.${EventLogSchema.PRODUCER} to $sys;
            grant select, insert, update on ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} to $sys;
            grant select on ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} to $sys;
            grant update (position, updated_at) on ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} to $sys;
            grant select on ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION} to $sys;
            """.trimIndent()
    }

    fun producerGrants(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val producerLogins = topic.producers.sorted().map { SqlIdentifier.require(it, "producer login") }
        return """
            grant usage on schema ${EventLogSchema.SCHEMA} to ${producerLogins.joinToString(", ")};
            grant insert on ${EventLogSchema.SCHEMA}.$topicKey to ${producerLogins.joinToString(", ")};
            grant execute on function ${EventLogSchema.SCHEMA}.bound_tenant() to ${producerLogins.joinToString(", ")};
            """.trimIndent()
    }

    fun subscriberGrants(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val subscriberLogins = topic.subscribers.sorted().map { SqlIdentifier.require(it, "subscriber login") }
        return """
            grant usage on schema ${EventLogSchema.SCHEMA} to ${subscriberLogins.joinToString(", ")};
            grant select on ${EventLogSchema.SCHEMA}.$topicKey to ${subscriberLogins.joinToString(", ")};
            grant execute on function ${EventLogSchema.SCHEMA}.bound_tenant() to ${subscriberLogins.joinToString(", ")};
            """.trimIndent()
    }

    fun subscriptionRows(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val subscriberLogins = topic.subscribers.sorted().map { SqlIdentifier.require(it, "subscriber login") }
        return """
            insert into ${EventLogSchema.SCHEMA}.${EventLogSchema.SUBSCRIPTION} (topic_key, login)
            select '$topicKey', unnest(array['${subscriberLogins.joinToString("', '")}']::text[]);
            """.trimIndent()
    }

    fun producerRows(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val producerLogins = topic.producers.sorted().map { SqlIdentifier.require(it, "producer login") }
        return """
            insert into ${EventLogSchema.SCHEMA}.${EventLogSchema.PRODUCER} (topic_key, login)
            select '$topicKey', unnest(array['${producerLogins.joinToString("', '")}']::text[]);
            """.trimIndent()
    }

    /** Removes the record-position rows of topics no longer marked as record topics. */
    fun staleRecordPositions(records: List<Topic>): String {
        val recordKeys = records.map { SqlIdentifier.require(tableName(it.name), "topic table") }
        return """
            delete from ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION}
            where topic_key <> all(array['${recordKeys.joinToString("', '")}']::text[]);
            """.trimIndent()
    }

    fun recordPositionRow(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val keeperLogin = SqlIdentifier.require(checkNotNull(topic.keeper), "keeper login")
        return """
            insert into ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION} (topic_key, keeper) values ('$topicKey', '$keeperLogin')
            on conflict (topic_key) do update set keeper = excluded.keeper;
            """.trimIndent()
    }

    /** Each subscriber reads its topic's watermark row and no other (INV-REQ-1205 b3, b6). */
    fun watermarkReads(
        topic: Topic,
        watermarkPolicy: String,
    ): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val policy = SqlIdentifier.require(watermarkPolicy, "watermark policy")
        val drop = "drop policy if exists $policy on ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK};"
        if (topic.subscribers.isEmpty()) return drop
        val subscriberLogins = topic.subscribers.sorted().map { SqlIdentifier.require(it, "subscriber login") }
        return """
            drop policy if exists $policy on ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK};
            create policy $policy on ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} for select to ${subscriberLogins.joinToString(", ")}
                using (topic_key = '$topicKey');
            grant select on ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK} to ${subscriberLogins.joinToString(", ")};
            """.trimIndent()
    }
}
