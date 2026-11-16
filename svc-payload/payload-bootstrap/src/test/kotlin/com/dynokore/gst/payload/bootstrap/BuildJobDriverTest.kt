package com.dynokore.gst.payload.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.payload.adapters.build.BuildJobFailed
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.atomic.AtomicInteger

/** INV-AC-23582: the build job driver claims jobs only when enabled. */
class BuildJobDriverTest {
    private class Queue(
        private var jobs: Int,
        private val failAt: Int = -1,
    ) : BuildJobStep {
        val claims = AtomicInteger()
        val failures = AtomicInteger()

        override fun runOnce(): Boolean {
            val n = claims.incrementAndGet()
            if (jobs == 0) return false
            if (n == failAt) {
                failures.incrementAndGet()
                error("job failed")
            }
            jobs--
            return true
        }
    }

    private fun runner(queue: BuildJobStep) =
        ApplicationContextRunner()
            .withBean(BuildJobStep::class.java, { queue })
            .withUserConfiguration(BuildJobDriverConfiguration::class.java)

    private fun awaitTrue(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
    }

    @Test
    fun `INV-AC-23582 disabled makes no claim`() {
        for (value in listOf(null, "false", "TRUE", "True", "1", "")) {
            val queue = Queue(2)
            var runner = runner(queue).withPropertyValues("payload.build.driver.delay=10ms")
            if (value != null) runner = runner.withPropertyValues("payload.build.driver.enabled=$value")
            runner.run { ctx ->
                ctx.containsBean("buildJobDriver") shouldBe false
                Thread.sleep(100)
                queue.claims.get() shouldBe 0
            }
        }
    }

    @Test
    fun `INV-AC-23582 enabled drains the queue`() {
        val queue = Queue(3)
        runner(queue)
            .withPropertyValues("payload.build.driver.enabled=true", "payload.build.driver.delay=10ms")
            .run { ctx ->
                ctx.containsBean("buildJobDriver") shouldBe true
                awaitTrue { queue.claims.get() >= 4 }
                // three jobs and the empty claim that ends the tick
                (queue.claims.get() >= 4) shouldBe true
            }
    }

    @Test
    fun `INV-AC-23582 failure does not stop the next tick`() {
        val queue = Queue(2, failAt = 1)
        BuildJobDriver(queue).tick() // first claim fails: the tick ends, nothing thrown
        queue.failures.get() shouldBe 1
        queue.claims.get() shouldBe 1
        BuildJobDriver(queue).tick() // the next tick drains both jobs and the empty claim
        queue.claims.get() shouldBe 4
        // and through the scheduler
        val scheduled = Queue(1, failAt = 1)
        runner(scheduled)
            .withPropertyValues("payload.build.driver.enabled=true", "payload.build.driver.delay=10ms")
            .run {
                awaitTrue { scheduled.claims.get() >= 3 }
                (scheduled.claims.get() >= 3) shouldBe true
            }
    }

    @Test
    fun `INV-AC-23582 enabled without delay fails to start`() {
        val queue = Queue(1)
        runner(queue).withPropertyValues("payload.build.driver.enabled=true").run { ctx ->
            (ctx.startupFailure != null) shouldBe true
            queue.claims.get() shouldBe 0
        }
    }

    @Test
    fun `INV-AC-23582 failure is logged with the document UUID`() {
        val logger = LoggerFactory.getLogger(BuildJobDriver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val doc = "7d1c1b0e-3f4a-4b8e-9a43-0d5a1c2e9f10"
            var first = true
            BuildJobDriver {
                if (first) {
                    first = false
                    throw BuildJobFailed(doc, IllegalStateException("boom"))
                }
                false
            }.tick()
            val event = appender.list.single()
            event.keyValuePairs.map { it.key to it.value } shouldBe listOf("docUuid" to doc)
        } finally {
            logger.detachAppender(appender)
        }
    }
}
