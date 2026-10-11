package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL and naming for the `eventlog` schema owned by the event binding (INV-REQ-1205, INV-ADR-283).
 * One table per topic, so the only grants on the schema are relay INSERT on the producer's own
 * topics and consumer SELECT on the topics it subscribes to. The event-log component's migration
 * runs [topicDdl] once per topic of `event-log/topics.yaml`; this module only inserts and selects.
 *
 * A topic table holds every tenant's events, so it is tenant data (INV-REQ-854, CS-19, Keel INV-Q-3251 option D):
 * `tenant_id uuid NOT NULL` (no default; a platform event is stored under `TenantId.PLATFORM`, INV-Q-2927 option D)
 * with tenancy-kernel's forced row-level security ([TenantRlsMigration.ddl]). A row without a tenant, or with a
 * tenant other than the one the inserting transaction has bound, is refused. Consumers read through the claim path
 * of ARC 4.3 step 1a, as the library already does for `event_dead_letter`: each consumer has two roles of its role
 * family (INV-Q-3247), given as [ConsumerRoles]:
 * - the **claim role** gets a `SELECT` policy `USING (true)` and column `SELECT` on `log_offset`, `tenant_id` and
 *   `inserted_at` only: it finds the records after an offset across tenants, and measures the backlog, but cannot
 *   read `envelope`, `event_id` or `subject`;
 * - the **reader role** gets `SELECT` under the tenant policy only: it reads `envelope` in a transaction bound to
 *   the record's tenant ([PostgresEventLogReader]).
 *
 * PostgreSQL combines a role's permissive policies with OR, so a role holding both the claim policy and the
 * envelope grant would read every tenant's envelope; [topicDdl] therefore refuses a role that is both. No role has
 * `BYPASSRLS`.
 *
 * Retention (INV-REQ-1205 b3, b4, b6; owner ruling INV-Q-3249): the event-log component's dedicated purge role gets
 * `DELETE` on every topic table through its own policies, column `SELECT` on `log_offset` and `inserted_at` only (never
 * `envelope` or `tenant_id`), and write on the [PURGE_WATERMARK] table ([watermarkDdl], run once before the topics).
 * That table holds one row per topic, the highest `log_offset` purged; it holds no tenant data (like
 * `consumer_offset`, INV-Q-2954), so it has no `tenant_id`. Its row-level security only scopes reading: each topic's
 * consumer roles read that topic's row and no other.
 */
object EventLogSchema {
    const val SCHEMA = "eventlog"

    /** The purge-watermark table of the `eventlog` schema: one row per purged topic. */
    const val PURGE_WATERMARK = "purge_watermark"

    /** The two event-log roles of one consuming service (see [EventLogSchema]); they must differ. */
    data class ConsumerRoles(
        val claimRole: String,
        val readerRole: String,
    ) {
        init {
            SqlIdentifier.require(claimRole, "claim role")
            SqlIdentifier.require(readerRole, "reader role")
            require(claimRole != readerRole) {
                "a consumer's claim role and reader role must differ (ARC 4.3 step 1a), both were '$claimRole'"
            }
        }
    }

    /** The table of [topic]: dots and hyphens become underscores (`gst.a.b-c.v1` is `gst_a_b_c_v1`). */
    fun tableName(topic: String): String {
        require(TOPIC.matches(topic)) { "topic must look like gst.<service>.<event-name>.v<N>, was '$topic'" }
        return SqlIdentifier.require(topic.replace('.', '_').replace('-', '_'), "topic table")
    }

    /**
     * `CREATE TABLE` of the [PURGE_WATERMARK] table (`topic`, `watermark` = highest purged `log_offset`, `updated_at`),
     * writable only by [purgeRole]. Run once by the event-log component's migration, before [topicDdl], which grants
     * each topic's consumer roles `SELECT` on that topic's row.
     */
    fun watermarkDdl(purgeRole: String): String {
        val purge = SqlIdentifier.require(purgeRole, "purge role")
        val table = "$SCHEMA.$PURGE_WATERMARK"
        return """
            |create table $table (
            |    topic text primary key,
            |    watermark bigint not null check (watermark >= 0),
            |    updated_at timestamptz not null default now()
            |);
            |comment on table $table is 'no tenant data: one row per event-log topic, highest purged log_offset';
            |alter table $table enable row level security;
            |alter table $table force row level security;
            |create policy $PURGE_POLICY on $table for all to $purge using (true) with check (true);
            |grant select, insert, update on $table to $purge;
        """.trimMargin()
    }

    /**
     * `CREATE TABLE` for [topic] with forced tenant row-level security, INSERT for [relayRole], for each of
     * [consumers] the claim policy and claim-column `SELECT` for its claim role and `SELECT` under the tenant policy
     * for its reader role, `SELECT` on the topic's [PURGE_WATERMARK] row for both, and for [purgeRole] `DELETE` with
     * column `SELECT` on `log_offset` and `inserted_at` only. Run [watermarkDdl] first.
     *
     * @throws IllegalArgumentException when a role is not a plain identifier, or one role would be both a claim role
     *   and a reader role (or the relay role) of this topic, or the purge role is also a relay or consumer role.
     */
    fun topicDdl(
        topic: String,
        relayRole: String,
        consumers: List<ConsumerRoles>,
        purgeRole: String,
    ): String {
        val name = tableName(topic)
        val table = "$SCHEMA.$name"
        val relay = SqlIdentifier.require(relayRole, "role")
        val purge = SqlIdentifier.require(purgeRole, "purge role")
        val claimRoles = consumers.map { it.claimRole }.distinct()
        val readerRoles = consumers.map { it.readerRole }.distinct()
        val overlap = claimRoles.intersect((readerRoles + relay).toSet())
        require(overlap.isEmpty()) {
            "a claim role may not also read envelopes or insert on $topic (ARC 4.3 step 1a), was $overlap"
        }
        require(purge !in claimRoles + readerRoles + relay) {
            "the purge role may not also insert or read on $topic (INV-REQ-1205 b3), was '$purge'"
        }
        val rls = TenantRlsMigration.ddl(SCHEMA, name, policyName = TENANT_POLICY)
        val claims =
            claimRoles.joinToString("\n") { role ->
                "create policy ${claimPolicy(role)} on $table for select to $role using (true);\n" +
                    "grant select (log_offset, tenant_id, inserted_at) on $table to $role;"
            }
        val reads = readerRoles.joinToString("\n") { "grant select on $table to $it;" }
        val watermarkReaders = (claimRoles + readerRoles).distinct()
        val watermark = "$SCHEMA.$PURGE_WATERMARK"
        val watermarkReads =
            if (watermarkReaders.isEmpty()) {
                ""
            } else {
                "create policy ${watermarkPolicy(name)} on $watermark " +
                    "for select to ${watermarkReaders.joinToString()} using (topic = '$topic');\n" +
                    "grant select on $watermark to ${watermarkReaders.joinToString()};"
            }
        return """
            |create table $table (
            |    log_offset bigint generated always as identity primary key,
            |    tenant_id uuid not null,
            |    event_id text not null,
            |    subject text not null,
            |    envelope jsonb not null,
            |    inserted_at timestamptz not null default now()
            |);
            |comment on column $table.envelope is 'class: content';
            |$rls
            |grant insert on $table to $relay;
            |$claims
            |$reads
            |$watermarkReads
            |create policy $PURGE_SCAN on $table for select to $purge using (true);
            |create policy $PURGE_DELETE on $table for delete to $purge using (true);
            |grant select (log_offset, inserted_at) on $table to $purge;
            |grant delete on $table to $purge;
        """.trimMargin()
    }

    /** The tenant policy's name on every topic table (policy names are per table, so one fixed name fits all). */
    const val TENANT_POLICY = "tenant_isolation"

    private fun claimPolicy(role: String): String = SqlIdentifier.require("claim_$role".take(MAX_IDENTIFIER), "policy")

    /** One policy per topic on the shared watermark table; a truncation clash fails the migration (duplicate name). */
    private fun watermarkPolicy(table: String): String = SqlIdentifier.require("wm_$table".take(MAX_IDENTIFIER), "policy")

    private const val PURGE_POLICY = "purge_write"
    private const val PURGE_SCAN = "purge_scan"
    private const val PURGE_DELETE = "purge_delete"

    private const val MAX_IDENTIFIER = 63
    private val TOPIC = Regex("^[a-z][a-z0-9.-]*$")
}
