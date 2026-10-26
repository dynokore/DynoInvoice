package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.jobs.FireResult
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/** The job runtime the composition builds, against the real database (INV-REQ-1136, INV-REQ-2405). */
class JobRuntimeWiringDbTest {
    @Test
    fun `INV-AC-2126 a scheduled job is registered with its criticality and each run is recorded`(
        @TempDir dir: Path,
    ) {
        TransportContext.online(dir).run { context ->
            val runtime = context.getBean(JobRuntime::class.java)
            runtime.register(job(JOB)) {}
            writeRetentionRow()
            runtime.announce()
            runtime.fire(JOB)[JobTarget.Whole] shouldBe FireResult.RAN

            runs(JOB) shouldContainExactly listOf("SUCCEEDED")
            outbox(PlatformEventCatalogue.JOB_REGISTERED.eventType, JOB) shouldBe 1
            outbox(PlatformEventCatalogue.JOB_RUN_RECORDED.eventType, JOB) shouldBe 1
        }
    }

    @Test
    fun `probe - a body that throws leaves a FAILED row and no SUCCEEDED row`(
        @TempDir dir: Path,
    ) {
        TransportContext.online(dir).run { context ->
            val runtime = context.getBean(JobRuntime::class.java)
            runtime.register(job(FAILING)) { error("boom") }
            writeRetentionRow()
            runtime.announce()
            runtime.fire(FAILING)

            runs(FAILING) shouldContainExactly listOf("FAILED")
        }
    }

    /** The row the migration runner writes at install (INV-Q-4346); this harness applies only the Flyway files. */
    private fun writeRetentionRow() =
        TransportDb.superuser().use { connection ->
            connection.createStatement().use {
                it.execute(
                    "insert into transport.job_retention (id, job_window) values (true, interval '2592000 seconds') " +
                        "on conflict do nothing",
                )
            }
        }

    private fun job(name: String) =
        JobDefinition(
            name,
            "transport-svc",
            JobEntityScope.PLATFORM,
            JobSchedule("0 0 * * * *"),
            JobCriticality.STANDARD,
            Duration.ofMinutes(5),
        )

    private fun runs(job: String): List<String> =
        TransportDb.superuser().use { connection ->
            connection.prepareStatement("select status from transport.job_run where job = ?").use {
                it.setString(1, job)
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }

    /** Outbox rows of [type] whose payload names [job]; the platform job `idempotency-key-purge` is not counted. */
    private fun outbox(
        type: String,
        job: String,
    ): Int =
        TransportDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select count(*) from transport.outbox where type = ? and data->>'job' = ?",
                ).use {
                    it.setString(1, type)
                    it.setString(2, job)
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    private companion object {
        const val JOB = "wiring-probe"
        const val FAILING = "wiring-probe-failing"
    }
}
