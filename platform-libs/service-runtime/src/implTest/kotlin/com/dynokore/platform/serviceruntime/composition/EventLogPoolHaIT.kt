package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.dynokore.platform.serviceruntime.datasource.withApplicationPool
import com.dynokore.platform.serviceruntime.datasource.withMountedLogin
import com.dynokore.platform.serviceruntime.datasource.withPool
import com.dynokore.platform.serviceruntime.testing.CATALOG_TABLES
import com.dynokore.platform.serviceruntime.testing.CURRENT_USER
import com.dynokore.platform.serviceruntime.testing.HeldTransaction
import com.dynokore.platform.serviceruntime.testing.PG_CLASS
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_NAME
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.serviceruntime.testing.createLoginSql
import com.dynokore.platform.serviceruntime.testing.startPostgres
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import javax.sql.DataSource

/**
 * The HA event-log instance (INV-REQ-1230 b2; owner ruling INV-Q-3955, coordinator ruling INV-Q-4348) with a second
 * PostgreSQL instance: the application pool connects to the application instance and the event-log pool, as the same
 * runtime login, to the event-log instance; it keeps one idle connection and holds at most three. It sets no
 * connection-init SQL and no schema, even when the application pool's is set (coordinator ruling INV-Q-4542).
 */
class EventLogPoolHaIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the event-log pool connects to the second instance, at most 3 connections, minimumIdle 1`(
        image: String,
        @TempDir mountRoot: Path,
    ) {
        val login = createLoginSql(RUNTIME, PASSWORD)
        startPostgres(image, login).use { application ->
            startPostgres(image, login + "create table public.event_log_marker (id integer);\n").use { eventLog ->
                tpl18Service()
                    .withPropertyValues(
                        "spring.datasource.url=${application.jdbcUrl}",
                        "spring.datasource.username=$RUNTIME",
                        "spring.datasource.hikari.connection-timeout=1000",
                        "gst.db.event-log-url=${eventLog.jdbcUrl}",
                    ).withMountedLogin(mountRoot, PASSWORD)
                    // refused at start-up (INV-Q-4542); set by a test post-processor that bypasses the first pass, and
                    // undone once the event-log pool exists, before the second pass (INV-Q-4603)
                    .withApplicationPool {
                        it.connectionInitSql = INIT_SQL
                        applicationPool = it
                    }.withPool(ServiceDataSources.EVENT_LOG) {
                        copiedInitSql = it.connectionInitSql
                        applicationInitSql = applicationPool?.connectionInitSql
                        applicationPool?.connectionInitSql = null
                    }.run { context ->
                        context.startupFailure shouldBe null
                        context.getBeanNamesForType(DataSource::class.java).toSet() shouldBe
                            setOf("dataSource", ServiceDataSources.EVENT_LOG)
                        val app = context.getBean(DataSource::class.java)
                        val pool = context.getBean(ServiceDataSources.EVENT_LOG, DataSource::class.java)
                        // the event-log pool does not copy the application pool's connection-init SQL (INV-Q-4542)
                        applicationInitSql shouldBe INIT_SQL
                        copiedInitSql shouldBe null
                        assertNoInitSqlNoSchema(pool)
                        TestDatabase(application.jdbcUrl, CATALOG_TABLES).use { db ->
                            val onApp = db.over(app)
                            val onEventLog = db.over(pool)
                            val marker = { access: TestDatabase.Access ->
                                access.asPlatform { fetchCount(PG_CLASS, PG_CLASS_NAME.eq("event_log_marker")) } == 1
                            }
                            marker(onApp) shouldBe false
                            marker(onEventLog) shouldBe true
                            onEventLog.asPlatform { select(CURRENT_USER).fetchSingle().value1() } shouldBe RUNTIME
                            // minimumIdle 1: the pool keeps an idle connection once started (INV-Q-4348)
                            (idleConnections(pool) >= 1) shouldBe true
                            // maximum 3: a fourth concurrent connection waits and times out
                            val held = (1..3).map { HeldTransaction(onEventLog) }
                            shouldThrow<Exception> { onEventLog.asPlatform { } }
                            held.forEach { it.close() }
                        }
                    }
            }
        }
    }

    // The pool reads sit in method bodies of this class, the one that holds the exempt-test line (INV-T-4790).
    private fun assertNoInitSqlNoSchema(pool: DataSource) {
        val hikari = pool as HikariDataSource
        hikari.connectionInitSql shouldBe null
        hikari.schema shouldBe null
    }

    private fun idleConnections(pool: DataSource): Int = (pool as HikariDataSource).hikariPoolMXBean.idleConnections

    private var applicationPool: HikariDataSource? = null
    private var applicationInitSql: String? = null
    private var copiedInitSql: String? = null

    private companion object {
        const val RUNTIME = "submission_runtime"
        const val PASSWORD = "runtime-pw"
        const val INIT_SQL = "select 1"
    }
}
