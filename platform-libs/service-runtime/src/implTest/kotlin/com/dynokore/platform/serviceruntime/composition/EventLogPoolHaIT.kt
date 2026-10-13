package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.postgres
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.sql
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.user
import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The HA event-log instance (INV-REQ-1230 b2; owner ruling INV-Q-3955, coordinator ruling INV-Q-4348) with a second
 * PostgreSQL instance: the application pool connects to the application instance and the event-log pool, as the same
 * runtime login, to the event-log instance; it keeps one idle connection and holds at most three.
 */
class EventLogPoolHaIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the event-log pool connects to the second instance, at most 3 connections, minimumIdle 1`(image: String) {
        postgres(image).use { application ->
            postgres(image).use { eventLog ->
                listOf(application, eventLog).forEach { it.sql("create role $RUNTIME login password '$PASSWORD'") }
                eventLog.sql("create table public.event_log_marker (id integer)")
                tpl18Service()
                    .withPropertyValues(
                        "spring.datasource.url=${application.jdbcUrl}",
                        "spring.datasource.username=$RUNTIME",
                        "spring.datasource.password=$PASSWORD",
                        "spring.datasource.hikari.connection-timeout=1000",
                        "gst.db.event-log-url=${eventLog.jdbcUrl}",
                    ).run { context ->
                        context.startupFailure shouldBe null
                        context.getBeanNamesForType(DataSource::class.java).toSet() shouldBe setOf("dataSource", ServiceDataSources.EVENT_LOG)
                        val app = context.getBean(DataSource::class.java)
                        val pool = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
                        app.connection.use { it.marker() shouldBe false }
                        pool.connection.use {
                            it.marker() shouldBe true
                            it.user() shouldBe RUNTIME
                        }
                        // minimumIdle 1: the pool keeps an idle connection once started (INV-Q-4348)
                        (pool.hikariPoolMXBean.idleConnections >= 1) shouldBe true
                        // maximum 3: a fourth concurrent connection waits and times out
                        val held = (1..3).map { pool.connection }
                        shouldThrow<SQLException> { pool.connection.close() }
                        held.forEach { it.close() }
                    }
            }
        }
    }

    private fun Connection.marker(): Boolean = createStatement().use { s ->
        s.executeQuery("select to_regclass('public.event_log_marker') is not null").use {
            it.next()
            it.getBoolean(1)
        }
    }

    private companion object {
        const val RUNTIME = "submission_runtime"
        const val PASSWORD = "runtime-pw"
    }
}
