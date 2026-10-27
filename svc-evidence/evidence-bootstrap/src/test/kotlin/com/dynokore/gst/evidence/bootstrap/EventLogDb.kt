package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.tenancykernel.BindingKeyMigration

/**
 * The `eventlog` schema of outbox-and-queue (its own DDL generator, [EventLogSchema]) for the two record topics, as a
 * migration login creates it and a sys login owns its functions, with evidence-svc's runtime login as their
 * subscriber and keeper. The returned SQL is the superuser setup of [EvidenceDb.withScratchDatabase].
 */
object EventLogDb {
    const val SYS = "eventlog_sys"
    const val MIGRATE = "eventlog_migrate"
    const val PURGE = "eventlog_purge"
    const val PURGE_PASSWORD = "eventlog_purge_pw"

    val topics: List<EventLogSchema.Topic> =
        listOf(
            RecordConsumerConfiguration.AUDIT_ENTRY_TOPIC,
            RecordConsumerConfiguration.DECISION_TRACE_TOPIC,
        ).map {
            EventLogSchema.Topic(
                name = it,
                producers = emptySet(),
                subscribers = setOf(EvidenceDb.RUNTIME),
                contentTopic = false,
                keeper = EvidenceDb.RUNTIME,
            )
        }

    fun key(topic: String): String = EventLogSchema.tableName(topic)

    private val keyHex =
        EvidenceDb.BINDING_SECRET.toByteArray(Charsets.UTF_8).joinToString("") {
            it.toUByte().toString(HEX_RADIX).padStart(2, '0')
        }

    fun setup(): String =
        buildString {
            appendLine(
                """
                DO ${'$'}${'$'}
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$SYS') THEN
                        CREATE ROLE $SYS NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$MIGRATE') THEN
                        CREATE ROLE $MIGRATE NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$PURGE') THEN
                        CREATE ROLE $PURGE LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '$PURGE_PASSWORD';
                    END IF;
                END ${'$'}${'$'};
                GRANT $SYS TO $MIGRATE;
                CREATE SCHEMA ${EventLogSchema.SCHEMA} AUTHORIZATION $MIGRATE;
                GRANT USAGE ON SCHEMA ${EventLogSchema.SCHEMA} TO ${EvidenceDb.RUNTIME};
                GRANT USAGE, CREATE ON SCHEMA ${EventLogSchema.SCHEMA} TO $SYS;
                SET ROLE $SYS;
                """.trimIndent(),
            )
            appendLine(BindingKeyMigration.ddl(EventLogSchema.SCHEMA, SYS, EvidenceDb.RUNTIME))
            appendLine(
                "INSERT INTO ${EventLogSchema.SCHEMA}.binding_key (slot, secret) " +
                    "VALUES ('current', decode('$keyHex', 'hex'));",
            )
            appendLine("RESET ROLE;")
            appendLine("REVOKE CREATE ON SCHEMA ${EventLogSchema.SCHEMA} FROM $SYS;")
            appendLine("SET ROLE $MIGRATE;")
            appendLine(EventLogSchema.schemaDdl(SYS))
            topics.forEach { appendLine(EventLogSchema.topicDdl(it, SYS)) }
            appendLine(EventLogSchema.functionsDdl(topics, SYS, PURGE))
            appendLine(
                "INSERT INTO ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION} " +
                    "(id, content_window, eventlog_retention, pack_retention, updated_at) VALUES " +
                    "(true, interval '30 days', interval '30 days', interval '1827 days', now());",
            )
            appendLine("RESET ROLE;")
        }

    private const val HEX_RADIX = 16
}
