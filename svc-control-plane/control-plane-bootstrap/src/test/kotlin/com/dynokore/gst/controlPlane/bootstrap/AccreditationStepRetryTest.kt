package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.nio.file.Files
import java.nio.file.Path

/** The job `accreditation-step-retry` and its configuration (INV-Q-17708 A): wiring, cron, fail-closed settings. */
class AccreditationStepRetryTest {
    private fun crons(
        mount: Path,
        vararg extra: String,
    ): List<String> {
        var expressions = emptyList<String>()
        controlPlaneRunner(mount, *extra).run { context ->
            context.startupFailure shouldBe null
            val registrar = ScheduledTaskRegistrar()
            context.getBean(AccreditationStepRetrySchedule::class.java).configureTasks(registrar)
            expressions = registrar.cronTaskList.map { it.expression }
        }
        return expressions
    }

    @Test
    fun `the job is a PLATFORM job of the service on the one job runtime`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount).run { context ->
            context.startupFailure shouldBe null
            val job = context.getBean(AccreditationStepRetryJob::class.java)
            job.definition.name shouldBe "accreditation-step-retry"
            job.definition.ownerService shouldBe "control-plane-svc"
            job.definition.entityScope shouldBe JobEntityScope.PLATFORM
        }
    }

    @Test
    fun `the schedule registers one cron task, on the property`(
        @TempDir mount: Path,
    ) {
        crons(mount) shouldBe listOf("0 * * * * *")
        crons(mount, "gst.jobs.accreditation-step-retry.cron=0 7 * * * *") shouldBe listOf("0 7 * * * *")
    }

    @Test
    fun `a missing or unusable retry property fails the start-up`(
        @TempDir mount: Path,
    ) {
        listOf(
            "gst.accreditation-step.first-delay",
            "gst.accreditation-step.first-backoff",
            "gst.accreditation-step.backoff-cap",
            "gst.accreditation-step.max-attempts",
            "gst.accreditation-step.batch-size",
            "gst.jobs.accreditation-step-retry.cron",
            "gst.jobs.accreditation-step-retry.tolerance",
        ).forEach { name ->
            controlPlaneRunner(mount, "$name=").run { context -> (context.startupFailure != null) shouldBe true }
        }
        controlPlaneRunner(mount, "gst.accreditation-step.max-attempts=0").run { context ->
            (context.startupFailure != null) shouldBe true
        }
    }

    @Test
    fun `the retry classes hold no cron expression, delay or attempt limit as a literal`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        val base = "src/main/kotlin/com/dynokore/gst/controlPlane"
        val files =
            listOf(
                "control-plane-bootstrap/$base/bootstrap/AccreditationStepRetryJob.kt",
                "control-plane-bootstrap/$base/bootstrap/AccreditationStepRetrySchedule.kt",
                "control-plane-adapters/$base/adapters/persistence/AccreditationStepQueueJooq.kt",
                "control-plane-application/$base/application/accreditation/AccreditationStepRetry.kt",
                "control-plane-application/$base/application/accreditation/OpenAccreditationTask.kt",
            )
        val forbidden =
            listOf(
                Regex("""\"[0-9*/, ]+ [0-9*/, ]+ [0-9*/, ?]+ [^\"]*\""""),
                Regex("""Duration\.of\w+\(\s*\d"""),
                Regex("""(maxAttempts|batchSize|limit)\s*(=|>=|<=|==|<|>)\s*\d"""),
                Regex("""\.limit\(\s*\d"""),
            )
        files.forEach { file ->
            val source = Files.readString(root.resolve(file))
            // only the literal 1 of the validation messages' bounds and the doubling are tolerated: none matches above
            forbidden.forEach { (it.containsMatchIn(source) to "$file $it") shouldBe (false to "$file $it") }
        }
    }
}
