package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.DSLContext
import org.jooq.Name
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
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
    /**
     * Extra provisioning SQL run once at container start, after the roles script, as the owner role (the script
     * begins with `SET ROLE outbox_owner`; a test switches with `RESET ROLE; SET ROLE ...` for the event log's
     * migration login). It is written to a temporary file and copied to the container's init directory
     * (INV-Q-5119), so a test needs no DDL connection after the start.
     */
    initSql: String = "",
) : AutoCloseable {
    private val script: Path? =
        if (initSql.isBlank()) {
            null
        } else {
            Files.createTempFile("outbox-init", ".sql").also {
                Files.writeString(it, rolesScript() + "\nSET ROLE $OWNER;\n" + initSql + "\nRESET ROLE;\n")
            }
        }

    private val container: Postgres =
        Postgres(DockerImageName.parse(image)).let {
            if (script == null) {
                it.withInitScript("db/outbox-and-queue-test-roles.sql")
            } else {
                it.withCopyFileToContainer(
                    MountableFile.forHostPath(script, READABLE),
                    "/docker-entrypoint-initdb.d/00-init.sql",
                )
            }
        }

    init {
        container.start()
    }

    /**
     * A staleness monitor over this fixture's own pools (route R3, INV-Q-5245): the runtime login for the offsets and the
     * event log's reader; it takes and returns no JDBC type.
     */
    fun stalenessMonitor(
        catalogue: EventCatalogue,
        schema: String,
        rules: List<StalenessRule>,
        clock: java.time.Clock,
        sink: StalenessBreachSink,
    ): JdbcStalenessMonitor {
        val scope = eventLogScope(TenantContextImpl())
        return JdbcStalenessMonitor(
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(schema), scope.tenants, scope.transactions),
            eventLogReader(),
            catalogue,
            schema,
            rules,
            clock,
            sink,
        )
    }

    /** An event-log reader over this fixture's own pools (route R3); it takes and returns no JDBC type. */
    fun eventLogReader(): PostgresEventLogReader {
        val scope = eventLogScope(TenantContextImpl())
        return PostgresEventLogReader(scope, eventLogDsl(scope))
    }

    /** An event-log reader on a fixture consumer's own login [role] (its replay topics, INV-Q-3601); route R3. */
    fun eventLogReaderFor(role: String): PostgresEventLogReader {
        val scope = eventLogScope(TenantContextImpl(), roleDataSource(role))
        return PostgresEventLogReader(
            scope,
            TenancyJooq.dslContext(eventLogTables(role, "${role}_pw"), scope.tenants, scope.transactions),
        )
    }

    /**
     * The event log's DSLContext over [scope]'s own runner with the tables [eventLogTables] finds (route R3): the
     * topic tables the runtime login can see, and the event log's functions and tenantless tables.
     */
    fun eventLogDsl(scope: TenantScope): DSLContext =
        TenancyJooq.dslContext(eventLogTables(), scope.tenants, scope.transactions)

    /**
     * The event log's tables as the production wiring declares them ([EventLogSchema.jooqTables]), the topic tables
     * taken from the catalog as the runtime login [APP] sees it (route R5): a topic table is every `eventlog` table of
     * that login which is not one of the event log's tenantless tables.
     */
    fun eventLogTables(
        login: String = APP,
        password: String = APP_PASSWORD,
    ): Map<Name, TableClass> {
        val tenantless = EventLogSchema.platformScopedTables().map { it.substringAfter('.') }.toSet()
        val tables =
            TenancyProbe.connect(jdbcUrl(), login, password).unbound {
                fetchRows(
                    "select table_name from information_schema.tables where table_schema = ?",
                    EventLogSchema.SCHEMA,
                ).map { it["table_name"] as String }
            }
        return EventLogSchema.jooqTables(emptyList()) +
            tables.filter { it !in tenantless }.associate { DSL.name(EventLogSchema.SCHEMA, it) to TableClass.TENANT }
    }

    /**
     * Writes the event log's retention row as the migration login [MIGRATE] does, with [EventLogSchema.writeRetention]
     * on its own pool, runner and DSLContext (route R4), in one transaction under the platform binding.
     */
    fun writeRetention(retention: Retention = Retention()) {
        val tenants = TenantContextImpl()
        val pool = TestPoolFixture(jdbcUrl(), MIGRATE, MIGRATE_PASSWORD, poolName = MIGRATE)
        try {
            val runner = pool.runner(tenants, null)
            val dsl = TenancyJooq.dslContext(EventLogSchema.jooqTables(emptyList()), tenants, runner)
            tenants.bindPlatform(WORKLOAD) { runner.inTransaction { EventLogSchema.writeRetention(dsl, retention) } }
        } finally {
            pool.close()
        }
    }

    /** The URL of the second database [HA_DATABASE] of the same instance (see [haEventLogInitSql]). */
    fun haEventLogUrl(): String = jdbcUrl().substringBefore("?").substringBeforeLast('/') + "/" + HA_DATABASE

    /** The container's JDBC URL, for [com.dynokore.platform.tenancykernel.TestPoolFixture] (INV-Q-5119). */
    fun jdbcUrl(): String = container.jdbcUrl

    /** The container superuser's login name and password (strings only). */
    fun adminUser(): String = container.username

    fun adminPassword(): String = container.password

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

    /** The container's superuser, for assertions that must see every tenant's rows (RLS is forced on the owner). */
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
        writeRetention(retention)
    }

    /** The event-log topics [createTopics] creates for [catalogue]. */
    fun eventLogTopics(catalogue: EventCatalogue): List<EventLogSchema.Topic> = topicsOf(catalogue)

    /** The runtime login a fixture consumer reads its replay topics with (`<prefix>_runtime`). */
    fun replayLogin(subscriber: String): String = replayLoginOf(subscriber)

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
        try {
            container.stop()
        } finally {
            if (script != null) Files.deleteIfExists(script)
        }
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
        /** The init script is read by the container's postgres user: mode 0644 (a temp file is 0600). */
        private const val READABLE = 420

        const val OWNER = "outbox_owner"
        const val HA_DATABASE = "ha_log"
        const val OWNER_PASSWORD = "outbox_owner_pw"
        const val APP_PASSWORD = "outbox_app_pw"
        const val WORKER_PASSWORD = "outbox_worker_pw"
        const val MIGRATE_PASSWORD = "eventlog_migrate_pw"

        /** The event-log topics for [catalogue] (see [createTopics]). */
        fun topicsOf(catalogue: EventCatalogue): List<EventLogSchema.Topic> {
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
                    EventLogSchema.Topic(it.topic, setOf(APP), setOf(replayLoginOf(it.subscriber)), it.contentTopic)
                }
            val requests =
                catalogue.requestTopics().map { EventLogSchema.Topic(it.topic, setOf(APP), setOf(APP), false) }
            return entries + replays + requests
        }

        fun replayLoginOf(subscriber: String): String {
            val prefix = REPLAY_LOGIN_PREFIX[subscriber] ?: error("no replay login for $subscriber in the fixture")
            return "${prefix}_runtime"
        }

        /** The service tables of [schema] as init SQL (what [createService] runs): the outbox DDL and [extraDdl]. */
        fun serviceInitSql(
            schema: String,
            extraDdl: String = "",
        ): String = OutboxSchema.ddl(schema, APP, SYS) + "\n" + extraDdl

        /**
         * The event log for [catalogue] as init SQL, as [createTopics] builds it: it switches to the event log's
         * migration login first and back to the owner after, so it can follow [serviceInitSql] in one init script.
         */
        fun eventLogInitSql(
            catalogue: EventCatalogue,
            retention: Retention = Retention(),
        ): String =
            buildString {
                append("RESET ROLE;\nSET ROLE $MIGRATE;\n")
                append(eventLogBody(catalogue, retention))
                append("RESET ROLE;\nSET ROLE $OWNER;\n")
            }

        /**
         * Init SQL for a second database, [HA_DATABASE], of the same instance that holds the event log (the HA form,
         * INV-REQ-1230 b2), created as its migration would: the schema is made over to the migration login, which then
         * runs the event-log DDL of [catalogue]. It ends in that database, so it comes last in an init script. The
         * runtime login reaches it through [haEventLogUrl].
         */
        fun haEventLogInitSql(
            catalogue: EventCatalogue,
            retention: Retention = Retention(),
        ): String =
            buildString {
                append("RESET ROLE;\ncreate database $HA_DATABASE;\n\\connect $HA_DATABASE\n")
                append("create schema ${EventLogSchema.SCHEMA} authorization $MIGRATE;\nSET ROLE $MIGRATE;\n")
                append(eventLogBody(catalogue, retention))
                append("RESET ROLE;\n")
            }

        private fun eventLogBody(
            catalogue: EventCatalogue,
            retention: Retention,
        ): String {
            val topics = topicsOf(catalogue)
            return buildString {
                append(EventLogSchema.schemaDdl(EVENTLOG_SYS)).append('\n')
                topics.forEach { append(EventLogSchema.topicDdl(it, EVENTLOG_SYS)).append('\n') }
                append(EventLogSchema.functionsDdl(topics, EVENTLOG_SYS, PURGE)).append('\n')
                append(
                    "insert into ${EventLogSchema.SCHEMA}.${EventLogSchema.RETENTION} " +
                        "(id, content_window, eventlog_retention, updated_at) values (true, " +
                        "${retention.contentWindow.seconds} * interval '1 second', " +
                        "${retention.eventLogRetention.seconds} * interval '1 second', now()) " +
                        "on conflict (id) do update set content_window = excluded.content_window, " +
                        "eventlog_retention = excluded.eventlog_retention, updated_at = excluded.updated_at;\n",
                )
            }
        }

        /**
         * Init SQL that records, for every row inserted into each of [tables] (`schema.table`, which must exist by
         * then), the transaction's `app.actor` (the actor tenancy-kernel bound, INV-REQ-854 b1) in
         * `public.captured_actor`, so a test can read it back (INV-Q-5119: the trigger is container provisioning).
         * It runs as the superuser for the capture table and function, then as the owner for the triggers.
         */
        fun captureActorsInitSql(tables: List<String>): String =
            buildString {
                append("RESET ROLE;\n")
                append(
                    "create table if not exists public.captured_actor " +
                        "(seq bigserial primary key, tbl text not null, actor text);\n",
                )
                append(
                    "create or replace function public.capture_actor() returns trigger " +
                        "language plpgsql security definer\n",
                )
                append("    set search_path = pg_catalog, public as ${'$'}${'$'}\n")
                append("begin\n")
                append(
                    "    insert into public.captured_actor (tbl, actor) values " +
                        "(tg_table_schema || '.' || tg_table_name, current_setting('app.actor', true));\n",
                )
                append("    return null;\n")
                append("end ${'$'}${'$'};\n")
                append("SET ROLE $OWNER;\n")
                tables.forEach {
                    append(
                        "create trigger capture_actor after insert on $it for each row " +
                            "execute function public.capture_actor();\n",
                    )
                }
            }

        private fun rolesScript(): String =
            checkNotNull(OutboxPostgresFixture::class.java.getResourceAsStream("/db/outbox-and-queue-test-roles.sql")) {
                "db/outbox-and-queue-test-roles.sql is missing"
            }.use { String(it.readAllBytes(), Charsets.UTF_8) }

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
