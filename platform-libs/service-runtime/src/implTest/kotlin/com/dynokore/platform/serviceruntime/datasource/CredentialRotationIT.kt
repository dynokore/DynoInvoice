package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.testing.BACKEND_PID
import com.dynokore.platform.serviceruntime.testing.CURRENT_USER
import com.dynokore.platform.serviceruntime.testing.HeldTransaction
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.serviceruntime.testing.changeOwnPassword
import com.dynokore.platform.serviceruntime.testing.createLoginSql
import com.dynokore.platform.serviceruntime.testing.startPostgres
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-3913 (STK-29, INV-REQ-1231; ruling INV-Q-3909) on PostgreSQL 17 and 18, with a MountedFileSecretStore over a
 * temporary mount, in one application context composed as TPL-18: a database role's password rotates with no restart.
 * Open connections keep their session; each new connection logs in with the password mounted at that moment, and the
 * revoked one no longer works. No password reaches the output or an exception message (CS-24). The roles are created
 * by the container's init SQL; a role changes its own password through its own login (TenancyProbe, route R5); a
 * connection held open is a transaction held open by a helper thread (route R6).
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
        val first = password()
        val second = password()
        val third = password()
        startPostgres(image, createLoginSql(APP, first)).use { pg ->
            mount(mountRoot, APP_REF, first)

            service(pg, mountRoot).run { context ->
                context.startupFailure shouldBe null
                val appSource = context.getBean(DataSource::class.java)
                val app = appSource as HikariDataSource
                // the event-log pool on its own URL takes the application pool's login and credentials provider
                val eventLog = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
                app.password.shouldBeNull()
                eventLog.password.shouldBeNull()
                TestDatabase(pg.jdbcUrl, emptyMap()).use { db ->
                    val pools = listOf(Triple(app, db.over(appSource), APP), Triple(eventLog, db.over(eventLog), APP))

                    // each pool holds an open connection, logged in with the first password
                    val open =
                        pools.map { (_, access, role) ->
                            HeldTransaction(access).also { it.user() shouldBe role }
                        }
                    val openPids = open.map { it.pid() }

                    // the role password changes; the mounted files are not rewritten yet
                    TenancyProbe.connect(pg.jdbcUrl, APP, first).changeOwnPassword(second)
                    open.forEach { it.user() } // open connections keep working
                    pools.forEach { (pool, _, _) -> pool.hikariPoolMXBean.softEvictConnections() }
                    // the first password is revoked: a new connection with the file left at it is refused (nothing cached)
                    pools.forEach { (_, access, role) ->
                        val refused = shouldThrow<Exception> { access.asPlatform { } }
                        val messages =
                            generateSequence<Throwable>(
                                refused,
                            ) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                        messages shouldContain "password authentication failed for user \"$role\""
                        listOf(first, second, third).forEach { messages shouldNotContain it }
                    }
                    open.forEach { it.user() } // still open

                    // the mounted files now carry the new password: new connections log in, with no restart
                    mount(mountRoot, APP_REF, second)
                    pools.forEach { (_, access, role) ->
                        val (user, pid) =
                            access.asPlatform {
                                select(CURRENT_USER, BACKEND_PID).fetchSingle().let {
                                    it.value1() to
                                        it.value2()
                                }
                            }
                        user shouldBe role
                        openPids shouldNotContain pid
                    }
                    open.forEach { it.user() }
                    open.forEach { it.close() } // returned after the eviction: retired, not reused

                    // a second rotation, again in the same context
                    TenancyProbe.connect(pg.jdbcUrl, APP, second).changeOwnPassword(third)
                    mount(mountRoot, APP_REF, third)
                    pools.forEach { (pool, _, _) -> pool.hikariPoolMXBean.softEvictConnections() }
                    pools.forEach { (_, access, role) ->
                        access.asPlatform { select(CURRENT_USER).fetchSingle().value1() } shouldBe role
                    }
                }
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
        val owner = password()
        val wrong = password()
        startPostgres(
            image,
            createLoginSql(OWNER, owner) +
                "do \$\$ begin execute format('grant create on database %I to $OWNER', current_database()); end \$\$;\n",
        ).use { pg ->
            // a wrong mounted password: the migration's connection is refused, and start-up fails
            mount(mountRoot, OWNER_REF, wrong)
            flyway(pg, mountRoot).run { context ->
                val messages =
                    generateSequence(context.startupFailure.shouldNotBeNull()) {
                        it.cause
                    }.joinToString(" | ") { it.message.orEmpty() }
                messages shouldContain "password authentication failed for user \"$OWNER\""
                messages shouldNotContain wrong
            }

            mount(mountRoot, OWNER_REF, owner)
            flyway(pg, mountRoot).run { context ->
                context.startupFailure shouldBe null
                val tables =
                    mapOf(
                        "pg_catalog.pg_tables" to TableClass.PLATFORM_SCOPED,
                        "ledger.flyway_schema_history" to TableClass.PLATFORM_SCOPED,
                    )
                TestDatabase(pg.jdbcUrl, tables).use { db ->
                    val reader = db.login(OWNER, owner)
                    val ownerOfProbe =
                        reader.asPlatform {
                            select(DSL.field(DSL.name("tableowner"), SQLDataType.VARCHAR))
                                .from(DSL.table(DSL.name("pg_catalog", "pg_tables")))
                                .where(DSL.field(DSL.name("schemaname")).eq("ledger"))
                                .and(DSL.field(DSL.name("tablename")).eq("probe"))
                                .fetchSingle()
                                .value1()
                        }
                    ownerOfProbe shouldBe OWNER
                    val migrated =
                        reader.asPlatform {
                            fetchCount(
                                DSL.table(DSL.name("ledger", "flyway_schema_history")),
                                DSL
                                    .field(DSL.name("success"), SQLDataType.BOOLEAN)
                                    .isTrue
                                    .and(DSL.field(DSL.name("version"), SQLDataType.VARCHAR).eq("1")),
                            )
                        }
                    migrated shouldBe 1
                }
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
                val messages =
                    generateSequence(context.startupFailure.shouldNotBeNull()) {
                        it.cause
                    }.joinToString(" | ") { it.message.orEmpty() }
                // the key check refuses it first, before any bean (coordinator rulings INV-Q-4548, INV-Q-4559)
                messages shouldContain "spring.flyway.password"
                messages shouldContain "INV-Q-4542"
                messages shouldNotContain "property-password-2"
            }
    }

    private fun service(
        pg: PostgreSQLContainer<*>,
        mountRoot: Path,
    ) = tpl18Service()
        .withPropertyValues(
            // the same instance under another URL, so the event-log pool is created (INV-REQ-1230 b2)
            // sslmode=prefer is pgjdbc's default; ApplicationName is refused (INV-Q-4559)
            "gst.db.event-log-url=${pg.jdbcUrl}&sslmode=prefer",
            "service-runtime.event-log-pool.minimum-idle=0",
            "gst.bindings.secret-store.mount-root=$mountRoot",
            "spring.datasource.url=${pg.jdbcUrl}",
            "spring.datasource.username=$APP",
            "spring.datasource.hikari.minimum-idle=0",
            // a pool of 3, all request permits (INV-REQ-1230 b1: the maximum is the sum of the lane budgets)
            "service-runtime.pool.lanes.request=3",
            "service-runtime.pool.lanes.background=0",
            "service-runtime.pool.lanes.reprocessing=0",
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
            "spring.flyway.enabled=true",
            "spring.flyway.user=$OWNER",
            "spring.flyway.schemas=ledger",
            "spring.flyway.locations=classpath:db/credential-rotation",
            "service-runtime.flyway.credential=$OWNER_REF",
        ).withMountedLogin(mountRoot, pg.password)

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
    }
}
