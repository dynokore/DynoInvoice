package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-3913 (STK-29, INV-REQ-1231; ruling INV-Q-3909) on PostgreSQL 17 and 18, with a MountedFileSecretStore over a
 * temporary mount, in one application context composed as TPL-18: a database role's password rotates with no restart.
 * Open connections keep their session; each new connection logs in with the password mounted at that moment, and the
 * revoked one no longer works. No password reaches the output or an exception message (CS-24).
 */
@ExtendWith(OutputCaptureExtension::class)
class CredentialRotationIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a rotated role password is used by new connections in the same context, and the revoked one is refused`(
        image: String,
        @TempDir mountRoot: Path,
        output: CapturedOutput,
    ) {
        postgres(image).use { pg ->
            val first = password()
            val second = password()
            val third = password()
            pg.sql("create role $APP login password '$first'")
            mount(mountRoot, APP_REF, first)

            service(pg, mountRoot).run { context ->
                context.startupFailure shouldBe null
                val app = context.getBean(DataSource::class.java) as HikariDataSource
                // the event-log pool on its own URL takes the application pool's login and credentials provider
                val eventLog = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
                app.password.shouldBeNull()
                eventLog.password.shouldBeNull()
                val pools = listOf(app to APP, eventLog to APP)

                // each pool holds an open connection, logged in with the first password
                val open = pools.map { (pool, role) -> pool.connection.also { it.user() shouldBe role } }
                val openPids = open.map { it.pid() }

                // the role passwords change; the mounted files are not rewritten yet
                pg.sql("alter role $APP password '$second'")
                open.forEach { it.user() } // open connections keep working
                pools.forEach { (pool, _) -> pool.hikariPoolMXBean.softEvictConnections() }
                // the first password is revoked: a new connection with the file left at it is refused (nothing cached)
                pools.forEach { (pool, role) ->
                    val refused = shouldThrow<java.sql.SQLException> { pool.connection.close() }
                    val messages = generateSequence<Throwable>(refused) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                    messages shouldContain "password authentication failed for user \"$role\""
                    listOf(first, second, third).forEach { messages shouldNotContain it }
                }
                open.forEach { it.user() } // still open

                // the mounted files now carry the new password: new connections log in, with no restart
                mount(mountRoot, APP_REF, second)
                pools.forEach { (pool, role) ->
                    pool.connection.use { fresh ->
                        fresh.user() shouldBe role
                        openPids shouldNotContain fresh.pid()
                    }
                }
                open.forEach { it.user() }
                open.forEach { it.close() } // returned after the eviction: retired, not reused

                // a second rotation, again in the same context
                pg.sql("alter role $APP password '$third'")
                mount(mountRoot, APP_REF, third)
                pools.forEach { (pool, _) -> pool.hikariPoolMXBean.softEvictConnections() }
                pools.forEach { (pool, role) -> pool.connection.use { it.user() shouldBe role } }
            }
            listOf(first, second, third).forEach { output.all shouldNotContain it }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `Flyway migrates as the owner role with its password read through SecretStore`(
        image: String,
        @TempDir mountRoot: Path,
        output: CapturedOutput,
    ) {
        postgres(image).use { pg ->
            val owner = password()
            val wrong = password()
            pg.sql("create role $OWNER login password '$owner'", "grant create on database ${pg.databaseName} to $OWNER")

            // a wrong mounted password: the migration's connection is refused, and start-up fails
            mount(mountRoot, OWNER_REF, wrong)
            flyway(pg, mountRoot).run { context ->
                val messages = generateSequence(context.startupFailure.shouldNotBeNull()) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                messages shouldContain "password authentication failed for user \"$OWNER\""
                messages shouldNotContain wrong
            }

            mount(mountRoot, OWNER_REF, owner)
            flyway(pg, mountRoot).run { context ->
                context.startupFailure shouldBe null
                pg.query("select tableowner from pg_tables where schemaname = 'ledger' and tablename = 'probe'") shouldBe OWNER
                pg.query("select count(*) from ledger.flyway_schema_history where success and version = '1'") shouldBe "1"
            }
            listOf(owner, wrong).forEach { output.all shouldNotContain it }
        }
    }

    @Test
    fun `start-up fails when spring-flyway-password is set as well`(
        @TempDir mountRoot: Path,
    ) {
        mount(mountRoot, OWNER_REF, "unused")
        tpl18Service()
            .withPropertyValues(
                "gst.bindings.secret-store.mount-root=$mountRoot",
                "spring.datasource.url=jdbc:postgresql://localhost:1/none",
                "spring.flyway.enabled=true",
                "spring.flyway.user=$OWNER",
                "spring.flyway.password=property-password-2",
                "service-runtime.flyway.credential=$OWNER_REF",
            ).run { context ->
                val messages = generateSequence(context.startupFailure.shouldNotBeNull()) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                messages shouldContain "so spring.flyway.password must not be set as well"
                messages shouldNotContain "property-password-2"
            }
    }

    private fun service(
        pg: PostgreSQLContainer<*>,
        mountRoot: Path,
    ) = tpl18Service()
        .withPropertyValues(
            // the same instance under another URL, so the event-log pool is created (INV-REQ-1230 b2)
            "gst.db.event-log-url=${pg.jdbcUrl}&ApplicationName=event-log",
            "service-runtime.event-log-pool.minimum-idle=0",
            "gst.bindings.secret-store.mount-root=$mountRoot",
            "spring.datasource.url=${pg.jdbcUrl}",
            "spring.datasource.username=$APP",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.maximum-pool-size=3",
            "spring.datasource.hikari.connection-timeout=1000",
            "service-runtime.datasource.credential=$APP_REF",
        )

    private fun flyway(
        pg: PostgreSQLContainer<*>,
        mountRoot: Path,
    ) = tpl18Service()
        .withPropertyValues(
            "gst.bindings.secret-store.mount-root=$mountRoot",
            "spring.datasource.url=${pg.jdbcUrl}",
            "spring.datasource.username=${pg.username}",
            "spring.datasource.password=${pg.password}",
            "spring.flyway.enabled=true",
            "spring.flyway.user=$OWNER",
            "spring.flyway.schemas=ledger",
            "spring.flyway.locations=classpath:db/credential-rotation",
            "service-runtime.flyway.credential=$OWNER_REF",
        )

    internal companion object {
        const val APP = "submission_app"
        const val OWNER = "submission_owner"
        const val APP_REF = "db-submission-app"
        const val OWNER_REF = "db-submission-owner"

        fun password() = "pw-${UUID.randomUUID()}"

        fun mount(
            root: Path,
            ref: String,
            value: String,
        ) {
            Files.writeString(root.resolve(ref), "$value\n")
        }

        fun postgres(image: String): PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse(image)).also { it.start() }

        fun PostgreSQLContainer<*>.sql(vararg statements: String) {
            DriverManager.getConnection(jdbcUrl, username, password).use { c -> statements.forEach { s -> c.createStatement().use { it.execute(s) } } }
        }

        fun PostgreSQLContainer<*>.query(sql: String): String = DriverManager.getConnection(jdbcUrl, username, password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getString(1)
                }
            }
        }

        fun Connection.user(): String = createStatement().use { s ->
            s.executeQuery("select current_user").use {
                it.next()
                it.getString(1)
            }
        }

        fun Connection.pid(): Int = createStatement().use { s ->
            s.executeQuery("select pg_backend_pid()").use {
                it.next()
                it.getInt(1)
            }
        }
    }
}
