package com.dynokore.gst.controlPlane.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.nio.file.Files
import java.nio.file.Path

/** The cron task that fires the job `pack-stage` (coordinator ruling INV-Q-8725): one task, on the property's cron. */
class PackStageScheduleTest {
    private fun crons(
        mount: Path,
        vararg extra: String,
    ): List<String> {
        var expressions = emptyList<String>()
        controlPlaneRunner(mount, *extra).run { context ->
            context.startupFailure shouldBe null
            val registrar = ScheduledTaskRegistrar()
            context.getBean(PackStageSchedule::class.java).configureTasks(registrar)
            expressions = registrar.cronTaskList.map { it.expression }
        }
        return expressions
    }

    @Test
    fun `the schedule registers exactly one cron task, on the cron of application yaml`(
        @TempDir mount: Path,
    ) {
        crons(mount) shouldBe listOf("0 */10 * * * *")
    }

    @Test
    fun `a test that sets another cron sees that value`(
        @TempDir mount: Path,
    ) {
        crons(mount, "gst.jobs.pack-stage.cron=0 5 * * * *") shouldBe listOf("0 5 * * * *")
    }

    @Test
    fun `the job class holds no scheduler of its own and no cron literal`() {
        val source =
            Files.readString(
                Path.of(
                    System.getProperty("user.dir"),
                    "src/main/kotlin/com/dynokore/gst/controlPlane/bootstrap/PackStageJob.kt",
                ),
            )
        Regex("newSingleThreadScheduledExecutor|SmartLifecycle").containsMatchIn(source) shouldBe false
        Regex("""\"[0-9*/, ]+ [0-9*/, ]+ [0-9*/, ?]+ [^\"]*\"""").containsMatchIn(source) shouldBe false
    }
}
