package com.dynokore.platform.outboxandqueue

/**
 * DDL and naming for the `eventlog` schema owned by the event binding (INV-REQ-1205, INV-ADR-283).
 * One table per topic, so the only grants on the schema are relay INSERT on the producer's own
 * topics and consumer SELECT on the topics it subscribes to. The event-log component's migration
 * runs [topicDdl] once per topic of `event-log/topics.yaml`; this module only inserts and selects.
 */
object EventLogSchema {
    const val SCHEMA = "eventlog"

    /** The table of [topic]: dots and hyphens become underscores (`gst.a.b-c.v1` is `gst_a_b_c_v1`). */
    fun tableName(topic: String): String {
        require(TOPIC.matches(topic)) { "topic must look like gst.<service>.<event-name>.v<N>, was '$topic'" }
        return SqlIdentifier.require(topic.replace('.', '_').replace('-', '_'), "topic table")
    }

    /** `CREATE TABLE` for [topic], INSERT for [relayRole] and SELECT for each of [consumerRoles]. */
    fun topicDdl(
        topic: String,
        relayRole: String,
        consumerRoles: List<String>,
    ): String {
        val table = "$SCHEMA.${tableName(topic)}"
        val grants =
            consumerRoles.joinToString("\n") { "grant select on $table to ${SqlIdentifier.require(it, "role")};" }
        return """
            create table $table (
                log_offset bigint generated always as identity primary key,
                event_id text not null,
                subject text not null,
                envelope jsonb not null,
                inserted_at timestamptz not null default now()
            );
            grant insert on $table to ${SqlIdentifier.require(relayRole, "role")};
            $grants
        """.trimIndent()
    }

    private val TOPIC = Regex("^[a-z][a-z0-9.-]*$")
}
