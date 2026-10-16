package com.dynokore.platform.serviceruntime.migration

import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.mount
import com.dynokore.platform.serviceruntime.datasource.Tpl18JdbcApplication
import com.dynokore.platform.serviceruntime.datasource.withMountedLogin
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE_NAME
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.serviceruntime.testing.createLoginSql
import com.dynokore.platform.serviceruntime.testing.startPostgres
import com.dynokore.platform.tenancyjooq.TableClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * The migration runner (INV-REQ-1189 b5; owner ruling INV-Q-3959) on PostgreSQL 16, 17 and 18: a service composed as
 * TPL-18 starts without migrating; the runner migrates as `<svc>_migrate` with its password read through SecretStore
 * (`service-runtime.flyway.credential`), even when the service's configuration turns Flyway off, and writes the
 * job-retention row from `service-runtime.jobs.retention` (coordinator ruling INV-Q-4346 (A)). It refuses a login that
 * is not a `<svc>_migrate` one and a run without the SecretStore credential.
 */
@ExtendWith(OutputCaptureExtension::class)
class MigrationRunnerIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the service starts without migrating, and the runner migrates as svc_migrate and writes the retention row`(
        image: String,
        @TempDir mountRoot: Path,
        output: CapturedOutput,
    ) {
        runRunner(image, mountRoot, output)
    }

    /**
     * The fixture service's migration (TPL-12) is a Flyway SQL file on the class path, `db/migration-it/V1__Jobs.sql`
     * (the key guard refuses a `filesystem:` location, CS-19): it is the job tables of JobSchema in `ledger`.
     */
    @Test
    fun `the committed migration resource is JobSchema ddl for ledger byte for byte`() {
        val resource =
            javaClass.classLoader
                .getResourceAsStream("db/migration-it/V1__Jobs.sql")
                .shouldNotBeNull()
                .use { String(it.readAllBytes(), Charsets.UTF_8) }
        resource shouldBe JobSchema.ddl("ledger", "submission_runtime", "submission_sys")
    }

    private fun runRunner(
        image: String,
        mountRoot: Path,
        output: CapturedOutput,
    ) {
        val migrate = "pw-${UUID.randomUUID()}"
        val roles =
            createLoginSql(MIGRATE, migrate) +
                createLoginSql(RUNTIME, "runtime-pw") +
                "create role $SYS nologin;\ngrant $SYS to $MIGRATE;\n" +
                "do \$\$ begin execute format('grant create on database %I to $MIGRATE', current_database()); end \$\$;\n"
        startPostgres(image, roles).use { pg ->
            mount(mountRoot, MIGRATE_REF, migrate)
            val database =
                arrayOf(
                    "spring.datasource.url=${pg.jdbcUrl}",
                    "gst.bindings.secret-store.mount-root=$mountRoot",
                    "spring.flyway.schemas=ledger",
                    "spring.flyway.locations=classpath:db/migration-it",
                )

            // the service: service-runtime's defaults turn Flyway off, so its context starts and migrates nothing
            ApplicationContextRunner()
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .withUserConfiguration(Tpl18JdbcApplication::class.java)
                .withPropertyValues(
                    *database,
                    "spring.datasource.username=$RUNTIME",
                    "spring.flyway.user=$MIGRATE",
                    "service-runtime.flyway.credential=$MIGRATE_REF",
                    "service-runtime.workload-identity.audience=submission-svc",
                    "gst.service=submission-svc",
                    "gst.instance-id=submission-svc-0",
                    "service-runtime.pool.profile=minimal",
                ).withMountedLogin(mountRoot, "runtime-pw")
                .run { context ->
                    context.startupFailure shouldBe null
                    context.beanDefinitionNames.filter {
                        context
                            .getType(
                                it,
                            )?.name == "org.flywaydb.core.Flyway"
                    } shouldBe
                        emptyList()
                    TestDatabase(pg.jdbcUrl, mapOf("pg_catalog.pg_namespace" to TableClass.PLATFORM_SCOPED)).use { db ->
                        db.login(RUNTIME, "runtime-pw").asPlatform {
                            fetchCount(PG_NAMESPACE, PG_NAMESPACE_NAME.eq("ledger"))
                        } shouldBe 0
                    }
                }

            // the runner, with the service's own `spring.flyway.enabled=false` in its configuration
            val args =
                (
                    database +
                        arrayOf(
                            "spring.flyway.enabled=false",
                            "spring.flyway.user=$MIGRATE",
                            "service-runtime.flyway.credential=$MIGRATE_REF",
                            "service-runtime.modules.schema=ledger",
                            "service-runtime.jobs.retention=2d",
                        )
                ).map { "--$it" }.toTypedArray()
            val tables =
                mapOf(
                    "pg_catalog.pg_tables" to TableClass.PLATFORM_SCOPED,
                    "ledger.flyway_schema_history" to TableClass.PLATFORM_SCOPED,
                    "ledger.job_retention" to TableClass.PLATFORM_SCOPED,
                )
            TestDatabase(pg.jdbcUrl, tables).use { db ->
                val reader = db.login(MIGRATE, migrate)
                val installers = DSL.field(DSL.name("installed_by"), SQLDataType.VARCHAR)
                val history = DSL.table(DSL.name("ledger", "flyway_schema_history"))
                val window = DSL.field(DSL.name("job_window"), SQLDataType.INTERVALDAYTOSECOND)

                MigrationRunner.run(*args).close()
                reader.asPlatform {
                    select(installers)
                        .from(history)
                        .where(DSL.field(DSL.name("success"), SQLDataType.BOOLEAN).isTrue)
                        .fetchSet(installers)
                } shouldBe setOf(MIGRATE)
                reader.asPlatform {
                    select(DSL.field(DSL.name("tableowner"), SQLDataType.VARCHAR))
                        .from(DSL.table(DSL.name("pg_catalog", "pg_tables")))
                        .where(DSL.field(DSL.name("schemaname")).eq("ledger"))
                        .and(DSL.field(DSL.name("tablename")).eq("job_run"))
                        .fetchSingle()
                        .value1()
                } shouldBe MIGRATE
                retention(reader, window) shouldBe Duration.ofDays(2)

                // a second run re-applies the configured retention with bound parameters
                MigrationRunner.run(*args.map { it.replace("retention=2d", "retention=3d") }.toTypedArray()).close()
                retention(reader, window) shouldBe Duration.ofDays(3)
            }
            output.all shouldNotContain migrate
        }
    }

    @Test
    fun `the runner refuses a login that is not svc_migrate and a run without the SecretStore credential`(
        @TempDir mountRoot: Path,
    ) {
        val base =
            arrayOf(
                "--spring.datasource.url=jdbc:postgresql://localhost:1/none",
                "--gst.bindings.secret-store.mount-root=$mountRoot",
            )
        val owner =
            shouldThrow<IllegalArgumentException> {
                MigrationRunner.run(
                    *base,
                    "--spring.flyway.user=submission_owner",
                    "--service-runtime.flyway.credential=$MIGRATE_REF",
                )
            }
        owner.message.orEmpty() shouldContain "<svc>_migrate"
        val noCredential =
            shouldThrow<IllegalArgumentException> { MigrationRunner.run(*base, "--spring.flyway.user=$MIGRATE") }
        noCredential.message.orEmpty() shouldContain "service-runtime.flyway.credential"
    }

    private fun retention(
        reader: TestDatabase.Access,
        window: org.jooq.Field<org.jooq.types.DayToSecond>,
    ): Duration =
        reader.asPlatform {
            select(window)
                .from(DSL.table(DSL.name("ledger", "job_retention")))
                .fetchSingle()
                .value1()
                .toDuration()
        }

    private companion object {
        const val MIGRATE = "submission_migrate"
        const val RUNTIME = "submission_runtime"
        const val SYS = "submission_sys"
        const val MIGRATE_REF = "db-submission-migrate"
    }
}
