package com.dynokore.platform.serviceruntime.migration

import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.mount
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.postgres
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.query
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.sql
import com.dynokore.platform.serviceruntime.datasource.Tpl18JdbcApplication
import com.dynokore.platform.serviceruntime.datasource.withMountedLogin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.nio.file.Path
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
        postgres(image).use { pg ->
            val migrate = "pw-${UUID.randomUUID()}"
            pg.sql(
                "create role $MIGRATE login password '$migrate'",
                "create role $RUNTIME login password 'runtime-pw'",
                "create role $SYS nologin",
                "grant $SYS to $MIGRATE",
                "grant create on database ${pg.databaseName} to $MIGRATE",
            )
            mount(mountRoot, MIGRATE_REF, migrate)
            val database =
                arrayOf(
                    "spring.datasource.url=${pg.jdbcUrl}",
                    "gst.bindings.secret-store.mount-root=$mountRoot",
                    "spring.flyway.schemas=ledger",
                    "spring.flyway.locations=classpath:db/migrationrunner",
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
                    context.getBeanNamesForType(Flyway::class.java).size shouldBe 0
                    pg.query("select count(*) from pg_namespace where nspname = 'ledger'") shouldBe "0"
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
            MigrationRunner.run(*args).close()
            pg.query(
                "select string_agg(distinct installed_by, ',') from ledger.flyway_schema_history where success",
            ) shouldBe
                MIGRATE
            pg.query("select tableowner from pg_tables where schemaname = 'ledger' and tablename = 'job_run'") shouldBe
                MIGRATE
            pg.query("select extract(epoch from job_window)::bigint from ledger.job_retention") shouldBe
                "${2 * 24 * 3600}"

            // a second run re-applies the configured retention with bound parameters
            MigrationRunner.run(*args.map { it.replace("retention=2d", "retention=3d") }.toTypedArray()).close()
            pg.query("select extract(epoch from job_window)::bigint from ledger.job_retention") shouldBe
                "${3 * 24 * 3600}"
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

    private companion object {
        const val MIGRATE = "submission_migrate"
        const val RUNTIME = "submission_runtime"
        const val SYS = "submission_sys"
        const val MIGRATE_REF = "db-submission-migrate"
    }
}
