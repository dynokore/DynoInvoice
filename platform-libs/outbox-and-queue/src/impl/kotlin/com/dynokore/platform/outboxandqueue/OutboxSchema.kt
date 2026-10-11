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
 * and a policy for the worker role only; the app role has no privilege on it, so only the worker reads
 * it, and a row without a tenant is invisible to every tenant session.
 */
object OutboxSchema {
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
            ${purgeAccess(s, worker)}
        """.trimIndent()
    }

    /**
     * The worker's retention purge access (INV-T-2998): `DELETE` on published outbox rows and on inbox rows, each
     * through its own policy, and the column `SELECT` the `WHERE` needs. The outbox policy cannot reach an unpublished
     * row, and the worker still cannot read `data` or any other outbox column beyond the claim grant.
     */
    private fun purgeAccess(
        s: String,
        worker: String,
    ): String = """
        drop policy if exists ${OUTBOX}_purge on $s.$OUTBOX;
        create policy ${OUTBOX}_purge on $s.$OUTBOX for delete to $worker using (published_at is not null);
        grant delete on $s.$OUTBOX to $worker;
        drop policy if exists ${INBOX}_purge on $s.$INBOX;
        drop policy if exists ${INBOX}_purge_select on $s.$INBOX;
        create policy ${INBOX}_purge_select on $s.$INBOX for select to $worker using (true);
        create policy ${INBOX}_purge on $s.$INBOX for delete to $worker using (true);
        grant select (consumer, event_id, received_at) on $s.$INBOX to $worker;
        grant delete on $s.$INBOX to $worker;
    """.trimIndent()

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
}
