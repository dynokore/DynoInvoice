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
 */
object EventLogSchema {
    const val SCHEMA = "eventlog"

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
     * `CREATE TABLE` for [topic] with forced tenant row-level security, INSERT for [relayRole], and for each of
     * [consumers] the claim policy and claim-column `SELECT` for its claim role and `SELECT` under the tenant policy
     * for its reader role.
     *
     * @throws IllegalArgumentException when a role is not a plain identifier, or one role would be both a claim role
     *   and a reader role (or the relay role) of this topic.
     */
    fun topicDdl(
        topic: String,
        relayRole: String,
        consumers: List<ConsumerRoles>,
    ): String {
        val name = tableName(topic)
        val table = "$SCHEMA.$name"
        val relay = SqlIdentifier.require(relayRole, "role")
        val claimRoles = consumers.map { it.claimRole }.distinct()
        val readerRoles = consumers.map { it.readerRole }.distinct()
        val overlap = claimRoles.intersect((readerRoles + relay).toSet())
        require(overlap.isEmpty()) {
            "a claim role may not also read envelopes or insert on $topic (ARC 4.3 step 1a), was $overlap"
        }
        val rls = TenantRlsMigration.ddl(SCHEMA, name, policyName = TENANT_POLICY)
        val claims =
            claimRoles.joinToString("\n") { role ->
                "create policy ${claimPolicy(role)} on $table for select to $role using (true);\n" +
                    "grant select (log_offset, tenant_id, inserted_at) on $table to $role;"
            }
        val reads = readerRoles.joinToString("\n") { "grant select on $table to $it;" }
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
        """.trimMargin()
    }

    /** The tenant policy's name on every topic table (policy names are per table, so one fixed name fits all). */
    const val TENANT_POLICY = "tenant_isolation"

    private fun claimPolicy(role: String): String = SqlIdentifier.require("claim_$role".take(MAX_IDENTIFIER), "policy")

    private const val MAX_IDENTIFIER = 63
    private val TOPIC = Regex("^[a-z][a-z0-9.-]*$")
}
