package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * Frozen copy of 1.2.0's `OutboxSchema.ddl` (verbatim apart from this name), so tests can build a real 1.2.0 schema and
 * prove `OutboxSchema.upgradeTo130` brings it to 1.3.0 (P20). Never edit it to follow the current DDL.
 */
@Suppress("LongMethod") // a verbatim frozen copy of 1.2.0, not to be restructured
object OutboxSchema120 {
    const val OUTBOX = "outbox"
    const val OUTBOX_SEQUENCE = "outbox_sequence"
    const val INBOX = "inbox"
    const val CONSUMER_SEQUENCE = "consumer_sequence"
    const val CONSUMER_OFFSET = "consumer_offset"
    const val DEAD_LETTER = "event_dead_letter"

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
            create policy ${DEAD_LETTER}_worker on $s.$DEAD_LETTER for all to $worker using (true) with check (true);
            create policy ${OUTBOX}_claim on $s.$OUTBOX for select to $worker using (true);
            grant usage on schema $s to $worker;
            grant select (seq, tenant_id, published_at) on $s.$OUTBOX to $worker;
            grant select, insert, update on $s.$CONSUMER_OFFSET to $worker;
            grant select, insert on $s.$DEAD_LETTER to $worker;
            grant update (status, resolved_at, note, replayed_to) on $s.$DEAD_LETTER to $worker;
            grant select, insert, update on $s.$OUTBOX, $s.$OUTBOX_SEQUENCE, $s.$CONSUMER_SEQUENCE,
                $s.$CONSUMER_OFFSET to $role;
            grant select, insert on $s.$INBOX to $role;
        """.trimIndent()
    }
}
