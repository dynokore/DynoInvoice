package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The `CREATE TABLE` statements and the publish-once trigger of [OutboxSchema.ddl] (split out of that object). */
internal object OutboxTablesSql {
    fun tables(schema: String): String =
        listOf(outboxTables(schema), inboxTables(schema), deadLetterTables(schema)).joinToString("\n")

    private fun outboxTables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.${OutboxSchema.OUTBOX} (
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
                published_at timestamptz,
                testmode boolean not null default false
            );
            create index ${OutboxSchema.OUTBOX}_unpublished on $s.${OutboxSchema.OUTBOX} (seq) where published_at is null;
            create index ${OutboxSchema.OUTBOX}_published_at on $s.${OutboxSchema.OUTBOX} (published_at) where published_at is not null;
            create table $s.${OutboxSchema.OUTBOX_SEQUENCE} (
                tenant_id uuid not null,
                aggregate_key text not null,
                last_value bigint not null,
                primary key (tenant_id, aggregate_key)
            );
            """.trimIndent()
    }

    private fun inboxTables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.${OutboxSchema.INBOX} (
                consumer text not null,
                event_id text not null,
                tenant_id uuid not null,
                received_at timestamptz not null default now(),
                primary key (consumer, event_id)
            );
            create index ${OutboxSchema.INBOX}_received_at on $s.${OutboxSchema.INBOX} (received_at);
            create table $s.${OutboxSchema.CONSUMER_SEQUENCE} (
                consumer text not null,
                source text not null,
                subject text not null,
                tenant_id uuid not null,
                last_value bigint not null,
                primary key (consumer, tenant_id, source, subject)
            );
            create table $s.${OutboxSchema.CONSUMER_OFFSET} (
                consumer text not null,
                topic text not null,
                next_offset bigint not null,
                attempts int not null default 0,
                infra_failures int not null default 0,
                first_infra_failure_at timestamptz,
                retry_not_before timestamptz,
                primary key (consumer, topic)
            );
            comment on table $s.${OutboxSchema.CONSUMER_OFFSET} is 'platform-scoped: no tenant data';
            """.trimIndent()
    }

    private fun deadLetterTables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.${OutboxSchema.DEAD_LETTER} (
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
            create index ${OutboxSchema.DEAD_LETTER}_resolved_at on $s.${OutboxSchema.DEAD_LETTER} (resolved_at) where resolved_at is not null;
            comment on column $s.${OutboxSchema.OUTBOX}.data is 'class: content';
            comment on column $s.${OutboxSchema.DEAD_LETTER}.raw is 'class: content';
            """.trimIndent()
    }

    /**
     * Outbox UPDATE hardening (owner ruling INV-Q-4334 B): the runtime login holds UPDATE on `published_at` only (no
     * DELETE; purging stays with [OutboxSchema.OUTBOX_PURGE_EXPIRED]), and [OutboxSchema.OUTBOX_PUBLISH_ONCE], a
     * BEFORE UPDATE trigger, lets
     * `published_at` change only from NULL, storing `clock_timestamp()` whatever value was passed. Re-setting,
     * clearing or backdating a published row and changing any other column are refused (SQLSTATE 42501), so a written
     * row's content and actor stay as written and no unpublished row is brought into the purge's range. The relay's
     * statement is unchanged. Marking an unrelayed row published at the current time stays possible: the residual
     * risk of INV-ADR-3968's consequences, detected by the ruling's follow-up task.
     */
    fun publishOnce(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create function $s.${OutboxSchema.OUTBOX_PUBLISH_ONCE}() returns trigger
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
            revoke all on function $s.${OutboxSchema.OUTBOX_PUBLISH_ONCE}() from public;
            create trigger ${OutboxSchema.OUTBOX_PUBLISH_ONCE} before update on $s.${OutboxSchema.OUTBOX}
                for each row execute function $s.${OutboxSchema.OUTBOX_PUBLISH_ONCE}();
            """.trimIndent()
    }

    fun replayTables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table if not exists $s.${OutboxSchema.CONSUMER_REPLAY} (
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
            comment on table $s.${OutboxSchema.CONSUMER_REPLAY} is 'platform-scoped: no tenant data';
            create unique index if not exists ${OutboxSchema.CONSUMER_REPLAY}_pending on $s.${OutboxSchema.CONSUMER_REPLAY} (consumer, producer)
                where closed_at is null;
            create table if not exists $s.${OutboxSchema.CONSUMER_REPLAY_MARKER} (
                replay_id uuid not null,
                source text not null,
                applied_at timestamptz not null default now(),
                primary key (replay_id, source)
            );
            comment on table $s.${OutboxSchema.CONSUMER_REPLAY_MARKER} is 'platform-scoped: no tenant data';
            create table if not exists $s.${OutboxSchema.CONSUMER_SOURCE_SEEN} (
                consumer text not null,
                producer text not null,
                source text not null,
                primary key (consumer, producer, source)
            );
            comment on table $s.${OutboxSchema.CONSUMER_SOURCE_SEEN} is 'platform-scoped: no tenant data';
            """.trimIndent()
    }
}
