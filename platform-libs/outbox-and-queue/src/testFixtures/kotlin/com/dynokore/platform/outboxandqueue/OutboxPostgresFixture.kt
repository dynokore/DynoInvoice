package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/** Testcontainers needs a concrete subclass for its fluent `self()` typing. */
private class Postgres(image: DockerImageName) : PostgreSQLContainer<Postgres>(image)

/**
 * A fresh PostgreSQL container (STK-14, STK-15; the PG 17 and 18 matrix, TPL-14) with the
 * roles of `outbox-and-queue-test-roles.sql`, and the service and event-log tables created
 * by the same generators a real migration would use ([OutboxSchema.ddl], [EventLogSchema.topicDdl]).
 */
class OutboxPostgresFixture(image: String) : AutoCloseable {
    private val container: Postgres =
        Postgres(DockerImageName.parse(image)).withInitScript("db/outbox-and-queue-test-roles.sql")

    init {
        container.start()
    }

    /** DDL-only owner role. */
    fun ownerDataSource(): DataSource = dataSourceFor("outbox_owner", "outbox_owner_pw")

    /** A service's runtime role (outbox, inbox, state tables), wrapped in tenancy-kernel's [TenantAwareDataSource]. */
    fun appDataSource(): DataSource = TenantAwareDataSource(dataSourceFor(APP, "outbox_app_pw"))

    /** The cross-tenant worker role (ARC 4.3 step 1a): claim policy and column grant only, never BYPASSRLS. */
    fun workerDataSource(): DataSource = dataSourceFor(WORKER, "outbox_worker_pw")

    /** The container's superuser, for test assertions that must see every tenant's rows (RLS is forced on the owner). */
    fun adminDataSource(): DataSource = dataSourceFor(container.username, container.password)

    /** The relay's event-log role: INSERT on topics only. */
    fun relayDataSource(): DataSource = dataSourceFor(RELAY, "outbox_relay_pw")

    /** The consumers' event-log role: SELECT on topics only. */
    fun readerDataSource(): DataSource = dataSourceFor(READER, "outbox_reader_pw")

    /** Creates the outbox/inbox tables in [schema] and grants [APP] on [extraTables] (state tables the test adds). */
    fun createService(
        schema: String,
        extraDdl: String = "",
    ) {
        run(OutboxSchema.ddl(schema, APP, WORKER) + "\n" + extraDdl)
    }

    /** Creates one event-log table per topic of [catalogue]. */
    fun createTopics(catalogue: EventCatalogue) {
        catalogue.entries().forEach { run(EventLogSchema.topicDdl(it.topic, RELAY, listOf(READER))) }
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
    ): DataSource = PGSimpleDataSource().apply {
        setUrl(container.jdbcUrl)
        this.user = user
        this.password = password
    }

    companion object {
        const val APP = "outbox_app"
        const val WORKER = "outbox_worker"
        const val RELAY = "outbox_relay"
        const val READER = "outbox_reader"
    }
}
