package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.ControlPlaneHarness
import com.dynokore.gst.controlPlane.adapters.PostgresDb
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The job runtime of the service's own composition against the real database (INV-AC-2126): the migrations have been
 * applied by the harness as controlplane_migrate, so Flyway is off in the context.
 */
class JobRuntimeIT {
    private fun withRuntime(
        mount: Path,
        block: (JobRuntime) -> Unit,
    ) {
        val database = PostgresDb.runtimeDataSource as PGSimpleDataSource
        // the migration Job's MigrationRunner writes the job_retention row;
        // the harness stands in for it
        ControlPlaneHarness
            .ownerDsl()
            .execute(
                "insert into controlplane.job_retention (id, job_window) values (true, interval '30 days') " +
                    "on conflict (id) do nothing",
            )
        // ... and the binding key the context's secret store holds, written as the key table's owner (controlplane_sys)
        ControlPlaneHarness.ownerDsl().transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.execute("set local role controlplane_sys")
            BindingKeyMigration.writeKeys(tx, "controlplane", BINDING_KEY)
        }
        // the pool's credential is a secret under the mount (service-runtime.datasource.credential), never a property
        Files.createDirectories(mount.resolve("db"))
        Files.writeString(mount.resolve("db").resolve("controlplane_runtime"), database.getPassword())
        controlPlaneRunner(
            mount,
            "spring.datasource.url=${database.getURL().substringBefore('?')}",
            "spring.datasource.username=${database.getUser()}",
            "spring.flyway.enabled=false",
        ).run { context: AssertableApplicationContext ->
            context.startupFailure shouldBe null
            block(context.getBean(JobRuntime::class.java))
        }
    }

    private companion object {
        val BINDING_KEY =
            object : BindingKeySource {
                override fun currentKey(): ByteArray = "test-installation-binding-key".toByteArray()

                override fun previousKey(): ByteArray? = null
            }
    }

    private fun job(name: String) =
        JobDefinition(
            name = name,
            ownerService = "control-plane-svc",
            entityScope = JobEntityScope.PLATFORM,
            schedule = JobSchedule("0 0 * * * *"),
            criticality = JobCriticality.STANDARD,
            tolerance = Duration.ofMinutes(5),
        )

    private fun runStatuses(name: String): List<String> =
        ControlPlaneHarness
            .ownerDsl()
            .select(DSL.field("status", String::class.java))
            .from(DSL.table("controlplane.job_run"))
            .where(DSL.field("job").eq(name))
            .fetch(DSL.field("status", String::class.java))

    private fun outboxTypes(name: String): List<String> =
        ControlPlaneHarness
            .ownerDsl()
            .select(DSL.field("type", String::class.java))
            .from(DSL.table("controlplane.outbox"))
            .where(DSL.field("data::text").like("%\"$name\"%"))
            .fetch(DSL.field("type", String::class.java))

    @Test
    fun `INV-AC-2126 a registered job records its run and emits JobRegistered and JobRunRecorded`(
        @TempDir mount: Path,
    ) {
        withRuntime(mount) { runtime ->
            runtime.register(job("wiring-probe")) { }
            runtime.announce()
            runtime.fire("wiring-probe")
            runtime.registered().filter { it.name == "wiring-probe" } shouldContainExactly listOf(job("wiring-probe"))
            runStatuses("wiring-probe") shouldContainExactly listOf("SUCCEEDED")
            val types = outboxTypes("wiring-probe")
            types.count { it == PlatformEventCatalogue.JOB_REGISTERED.eventType } shouldBe 1
            types.count { it == PlatformEventCatalogue.JOB_RUN_RECORDED.eventType } shouldBe 1
        }
    }

    @Test
    fun `a job body that throws leaves a FAILED run and no SUCCEEDED run`(
        @TempDir mount: Path,
    ) {
        withRuntime(mount) { runtime ->
            runtime.register(job("wiring-probe-failing")) { error("probe failure") }
            runtime.announce()
            runtime.fire("wiring-probe-failing")
            runStatuses("wiring-probe-failing") shouldContainExactly listOf("FAILED")
        }
    }
}
