package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/** Testcontainers needs a concrete subclass for its fluent `self()` typing. */
private class Postgres(
    image: DockerImageName,
) : PostgreSQLContainer<Postgres>(image)

/**
 * A fresh PostgreSQL container (STK-14, STK-15; the PG 16, 17 and 18 matrix, TPL-14, owner ruling INV-Q-3965) with the
 * roles of `outbox-and-queue-test-roles.sql`, and the service and event-log tables created
 * by the same generators a real migration would use ([OutboxSchema.ddl], [EventLogSchema.schemaDdl],
 * [EventLogSchema.topicDdl], [EventLogSchema.functionsDdl], [EventLogSchema.writeRetention]).
 */
class OutboxPostgresFixture(
    image: String,
) : AutoCloseable {
    private val container: Postgres =
        Postgres(DockerImageName.parse(image)).withInitScript("db/outbox-and-queue-test-roles.sql")

    init {
        container.start()
    }

    /** DDL-only owner role. */
    fun ownerDataSource(): DataSource = dataSourceFor("outbox_owner", "outbox_owner_pw")

    /** A service's runtime role (outbox, inbox, state tables), wrapped in tenancy-kernel's [TenantAwareDataSource]. */
    fun appDataSource(): DataSource = TenantAwareDataSource(dataSourceFor(APP, "outbox_app_pw"))

    /**
     * The same runtime login, unwrapped: the connections the relay, work queue, consumers, triage and purge use
     * outside any tenant transaction, reaching across tenants only through the [SYS]-owned views and functions.
     */
    fun runtimeDataSource(): DataSource = dataSourceFor(APP, "outbox_app_pw")

    /** The role of service-runtime's job and idempotency tables (ARC 4.3 step 1a); no event-log right. */
    fun workerDataSource(): DataSource = dataSourceFor(WORKER, "outbox_worker_pw")

    /** The container's superuser, for test assertions that must see every tenant's rows (RLS is forced on the owner). */
    fun adminDataSource(): DataSource = dataSourceFor(container.username, container.password)

    /**
     * The fixture services' event-log login, the runtime login [APP] (INV-Q-4268): it produces and subscribes to the
     * catalogue's topics and reaches across tenants only through `eventlog.claim`.
     */
    fun eventLogDataSource(): DataSource = runtimeDataSource()

    /** The event log's migration login ([MIGRATE], owner of schema `eventlog`, member of [EVENTLOG_SYS]). */
    fun migrateDataSource(): DataSource = dataSourceFor(MIGRATE, "eventlog_migrate_pw")

    /** The event-log component's purge login: EXECUTE on `eventlog.purge_batch()` only (INV-Q-3249). */
    fun purgeDataSource(): DataSource = dataSourceFor(PURGE, "eventlog_purge_pw")

    /**
     * A [TenantScope] over the event log's own transactions on [source] (its own [TransactionRunnerImpl], as the
     * service-runtime wiring builds it), binding [tenants] with the workload principal [principal].
     */
    fun eventLogScope(
        tenants: TenantContextImpl,
        source: DataSource = eventLogDataSource(),
        principal: String = WORKLOAD,
    ): TenantScope {
        val transactions = TransactionRunnerImpl(TenantAwareDataSource(source), tenants)
        return TenantScope(tenants, transactions, transactions, principal)
    }

    /**
     * Creates the outbox/inbox tables in [schema] for the runtime login [APP] and the sys role [SYS], and runs
     * [extraDdl] (state tables the test adds).
     */
    fun createService(
        schema: String,
        extraDdl: String = "",
    ) {
        run(OutboxSchema.ddl(schema, APP, SYS) + "\n" + extraDdl)
    }

    /**
     * Creates the event log for [catalogue] as its migration would: [EventLogSchema.schemaDdl], one
     * [EventLogSchema.topicDdl] per topic, [EventLogSchema.functionsDdl] over the whole set and the retention row
     * ([retention], written with bound parameters). Every catalogued and request topic is produced and subscribed by
     * [APP], which keeps the record topics' positions; a replay topic is subscribed by its one consumer's own login
     * ([replayLogin], INV-Q-3601).
     */
    fun createTopics(
        catalogue: EventCatalogue,
        retention: Retention = Retention(),
    ) {
        val topics = eventLogTopics(catalogue)
        runEventLog(EventLogSchema.schemaDdl(EVENTLOG_SYS))
        topics.forEach { runEventLog(EventLogSchema.topicDdl(it, EVENTLOG_SYS)) }
        runEventLog(EventLogSchema.functionsDdl(topics, EVENTLOG_SYS, PURGE))
        migrateDataSource().connection.use { EventLogSchema.writeRetention(it, retention) }
    }

    /** The event-log topics [createTopics] creates for [catalogue]. */
    fun eventLogTopics(catalogue: EventCatalogue): List<EventLogSchema.Topic> {
        val entries =
            catalogue.entries().groupBy { it.topic }.map { (topic, rows) ->
                val record = rows.any { it.recordTopic }
                EventLogSchema.Topic(
                    topic,
                    setOf(APP),
                    setOf(APP),
                    rows.any { it.contentTopic == true },
                    if (record) APP else null,
                )
            }
        val replays =
            catalogue.replayTopics().map {
                EventLogSchema.Topic(it.topic, setOf(APP), setOf(replayLogin(it.subscriber)), it.contentTopic)
            }
        val requests = catalogue.requestTopics().map { EventLogSchema.Topic(it.topic, setOf(APP), setOf(APP), false) }
        return entries + replays + requests
    }

    /** The runtime login a fixture consumer reads its replay topics with (`<prefix>_runtime`). */
    fun replayLogin(subscriber: String): String {
        val prefix = REPLAY_LOGIN_PREFIX[subscriber] ?: error("no replay login for $subscriber in the fixture")
        return "${prefix}_runtime"
    }

    /** A login data source for one of the fixture's logins ([replayLogin], or [APP]). */
    fun roleDataSource(role: String): DataSource = dataSourceFor(role, "${role}_pw")

    /** Runs [sql] as the event log's migration login [MIGRATE]. */
    fun runEventLog(sql: String) {
        migrateDataSource().connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    /**
     * Records, for every row inserted into [table] (`schema.table`) from now on, the transaction's `app.actor` (the
     * actor tenancy-kernel bound, INV-REQ-854 b1), so a test can read it back with [capturedActors]; forgets what an
     * earlier call captured on [table].
     */
    fun captureActors(table: String) {
        runAsAdmin(
            """
            create table if not exists public.captured_actor (seq bigserial primary key, tbl text not null, actor text);
            create or replace function public.capture_actor() returns trigger language plpgsql security definer
                set search_path = pg_catalog, public as ${'$'}${'$'}
            begin
                insert into public.captured_actor (tbl, actor) values (tg_table_schema || '.' || tg_table_name, current_setting('app.actor', true));
                return null;
            end ${'$'}${'$'};
            delete from public.captured_actor where tbl = '$table';
            drop trigger if exists capture_actor on $table;
            create trigger capture_actor after insert on $table for each row execute function public.capture_actor();
            """.trimIndent(),
        )
    }

    /** The `app.actor` of each row inserted into [table] since [captureActors], in insert order. */
    fun capturedActors(table: String): List<String?> =
        adminDataSource().connection.use { connection ->
            connection.prepareStatement("select actor from public.captured_actor where tbl = ? order by seq").use {
                it.setString(1, table)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    /** Runs [sql] as the superuser. */
    fun runAsAdmin(sql: String) {
        adminDataSource().connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    /** Runs [sql] as the owner role. */
    fun run(sql: String) {
        ownerDataSource().connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    override fun close() {
        container.stop()
    }

    private fun dataSourceFor(
        user: String,
        password: String,
    ): DataSource =
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            this.user = user
            this.password = password
        }

    companion object {
        const val APP = "outbox_app"
        const val SYS = "outbox_sys"
        const val WORKER = "outbox_worker"
        const val EVENTLOG_SYS = "eventlog_sys"
        const val MIGRATE = "eventlog_migrate"
        const val PURGE = "eventlog_purge"

        /** The fixture services' workload principal (INV-REQ-854 b1). */
        const val WORKLOAD = "svc:outbox-test"
        private val REPLAY_LOGIN_PREFIX =
            mapOf("reconciliation-svc" to "recon", "submission-svc" to "sub", "evidence-svc" to "evidence")
    }
}
