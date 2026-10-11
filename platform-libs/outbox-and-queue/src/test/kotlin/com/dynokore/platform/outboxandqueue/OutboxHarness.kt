package com.dynokore.platform.outboxandqueue

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * One PostgreSQL container (17 or 18) with the three fixture services' schemas, their state
 * tables, and the event log with one table per catalogued topic. The event log is reached
 * through [eventLogForRelay], a [FlakyDataSource] the outage test can switch off.
 */
class OutboxHarness(image: String) : AutoCloseable {
    val fixture = OutboxPostgresFixture(image)
    val catalogue: EventCatalogue = TestCatalogue.load()

    /** [catalogue] with no record topic: a platform topic then keeps the docsequence rule (INV-Q-3809). */
    val sequencedCatalogue: EventCatalogue =
        catalogue.entries().map { it.copy(recordTopic = false) }.let {
            EventCatalogue(it, catalogue.replayTopics(), catalogue.requestTopics())
        }
    val eventLogForRelay = FlakyDataSource(fixture.relayDataSource())
    val reader = PostgresEventLogReader(fixture.workerDataSource(), fixture.readerDataSource())
    val mapper = ObjectMapper()
    private val json = EnvelopeJson()

    /** Every 'rebuild required' incident raised by a consumer built here (INV-REQ-1205 b6). */
    val rebuildIncidents: MutableList<RebuildRequiredIncident> = java.util.Collections.synchronizedList(mutableListOf())
    val closed: MutableList<RebuildRequiredClosed> = java.util.Collections.synchronizedList(mutableListOf())
    val rebuildSink =
        object : RebuildRequiredSink {
            override fun raise(incident: RebuildRequiredIncident) {
                rebuildIncidents += incident
            }

            override fun close(closed: RebuildRequiredClosed) {
                this@OutboxHarness.closed += closed
            }
        }

    init {
        fixture.createService("submission", SUBMISSION_STATE + QueueSchema.ddl("submission", APP, WORKER))
        fixture.createService("reconciliation", RECONCILIATION_STATE)
        fixture.createService("payload")
        fixture.createService("canonical")
        fixture.createService("evidence")
        fixture.createTopics(catalogue)
    }

    val submission = ServiceKit(this, "submission", "submission-svc", catalogue)
    val payload = ServiceKit(this, "payload", "payload-svc", catalogue)
    val reconciliation = ServiceKit(this, "reconciliation", "reconciliation-svc", catalogue)
    val canonical = ServiceKit(this, "canonical", "canonical-svc", catalogue)
    val evidence = ServiceKit(this, "evidence", "evidence-svc", catalogue)

    /** Everything on [topic], decoded. */
    fun logOf(topic: String): List<Envelope> = reader.read(topic, 0, LOG_LIMIT).map { json.read(it.envelopeJson) }

    /** A consumer of [topic] named [name] in [schema], over [tx]. */
    fun consumer(
        name: String,
        topic: String,
        schema: String,
        tx: KillableTransactionRunner,
        handler: EventHandler,
        clock: java.time.Clock = java.time.Clock.systemUTC(),
    ) = JdbcInboxConsumer(
        ConsumerSubscription(name, topic, schema),
        catalogue,
        reader,
        fixture.workerDataSource(),
        TenantScope(tx.tenants, tx, tx),
        handler,
        rebuildSink,
        clock,
    )

    /** `[relrowsecurity, relforcerowsecurity]` of `schema.table`. */
    fun flags(
        schema: String,
        table: String,
    ): List<Boolean> = fixture.adminDataSource().connection.use { connection ->
        connection
            .prepareStatement(
                "select relrowsecurity, relforcerowsecurity from pg_class " +
                    "where oid = (?::text)::regclass",
            ).use { statement ->
                statement.setString(1, "$schema.$table")
                statement.executeQuery().use {
                    it.next()
                    listOf(it.getBoolean(1), it.getBoolean(2))
                }
            }
    }

    /** The raw CloudEvents JSON of every record in the event-log table [logTable] (`eventlog.<table>`). */
    fun wire(logTable: String): List<JsonNode> = fixture.adminDataSource().connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select envelope::text from $logTable order by log_offset").use { rows ->
                generateSequence { if (rows.next()) mapper.readTree(rows.getString(1)) else null }.toList()
            }
        }
    }

    /** `is_nullable` of the `tenant_id` column of `schema.table`, as `information_schema` reports it. */
    fun nullable(
        schema: String,
        table: String,
    ): List<String> = fixture.adminDataSource().connection.use { connection ->
        connection
            .prepareStatement(
                "select is_nullable from information_schema.columns " +
                    "where table_schema = ? and table_name = ? and column_name = 'tenant_id'",
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, table)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
    }

    /** Rows of [table] whose [column] equals [value]. */
    fun rows(
        table: String,
        column: String,
        value: Any,
    ): Long = count("select count(*) from $table where $column = ?", value)

    /** Runs [relay] until nothing is pending (bounded). */
    fun drain(relay: OutboxRelay) {
        repeat(MAX_RUNS) {
            val outcome = relay.runOnce()
            if (outcome.pending == 0L || outcome.stalled) return
        }
    }

    /** The consumer's stored position on [topic] in [schema], or null when it has none. */
    fun nextOffsetOrNull(
        schema: String,
        consumer: String,
        topic: String,
    ): Long? = fixture.adminDataSource().connection.use { connection ->
        val sql = "select next_offset from $schema.consumer_offset where consumer = ? and topic = ?"
        connection.prepareStatement(sql).use {
            it.setString(1, consumer)
            it.setString(2, topic)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
    }

    fun count(
        sql: String,
        vararg args: Any,
    ): Long = fixture.adminDataSource().connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    override fun close() {
        fixture.close()
    }

    companion object {
        const val MAX_RUNS = 1000
        const val LOG_LIMIT = 10_000
        val IMAGES = listOf("postgres:17", "postgres:18")

        private const val APP = OutboxPostgresFixture.APP
        private const val WORKER = OutboxPostgresFixture.WORKER
        private const val SUBMISSION_STATE = """
            create table submission.submission_state (doc_uuid uuid primary key, status text not null);
            create table submission.obligation_register (
                doc_uuid uuid primary key, canonical_seen boolean not null, payload_seen boolean not null,
                state text generated always as (
                    case when canonical_seen and payload_seen then 'COMPLETE' else 'PARTIAL' end
                ) stored);
            create table submission.queue_item (
                doc_uuid uuid not null, version int not null, primary key (doc_uuid, version));
            create table submission.submission_row (
                doc_uuid uuid not null, version int not null, primary key (doc_uuid, version));
            grant select, insert, update on submission.submission_state, submission.obligation_register,
                submission.queue_item, submission.submission_row to $APP;
        """
        private const val RECONCILIATION_STATE = """
            create table reconciliation.ack_read_model (doc_uuid uuid primary key, ack_count int not null);
            grant select, insert, update on reconciliation.ack_read_model to $APP;
        """
    }
}
