package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.serviceruntime.composition.JobAnnouncement
import com.dynokore.platform.serviceruntime.composition.ServiceRuntimeWiring
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.impl.DSL
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

/**
 * INV-T-20639, INV-AC-20638 (INV-REQ-1226): service-runtime itself calls `JobRuntime.announce()` once on
 * `ApplicationReadyEvent`, through the [JobAnnouncement] bean of [ServiceRuntimeWiring]; `announce()` runs at most once
 * per [JobRuntime]. The context is the wiring over a replica's pool and its runtime; the outbox is read through the
 * harness helpers.
 */
class JobAnnouncementIT {
    private val retentionTable = DSL.table(DSL.name(SCHEMA, JobSchema.JOB_RETENTION))

    private fun base(h: JobsHarness): ApplicationContextRunner = ApplicationContextRunner().withConfiguration(wiring())

    private fun wiring() = AutoConfigurations.of(ServiceRuntimeWiring::class.java)

    private fun ready(context: ConfigurableApplicationContext) =
        context.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), context, Duration.ZERO))

    private fun announcedJobs(h: JobsHarness): List<String> =
        h
            .rows("$SCHEMA.outbox", "type" to JobPayloads.JOB_REGISTERED)
            .map { ObjectMapper().readTree(it["data"] as String)["job"].asString() }
            .sorted()

    private fun JobsHarness.runner(replica: JobsHarness.Replica) =
        replica.withDataSourceBean(base(this).withBean(JobRuntime::class.java, { replica.runtime }))

    private fun twoJobs(replica: JobsHarness.Replica): List<String> =
        listOf("ac20638-a", "ac20638-b").onEach {
            replica.runtime.register(job(it, scope = JobEntityScope.PLATFORM)) { }
        }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `INV-AC-20638 a context with a job runtime announces one JobRegistered per job on the first ready event only`(
        image: String,
    ) {
        JobsHarness(image).use { h ->
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            val names = twoJobs(replica)
            h.runner(replica).run { context ->
                announcedJobs(h).shouldBeEmpty()
                ready(context)
                announcedJobs(h) shouldBe names
                ready(context)
                announcedJobs(h) shouldBe names
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `INV-AC-20638 a direct announce before the ready event leaves one JobRegistered per job`(image: String) {
        JobsHarness(image).use { h ->
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            val names = twoJobs(replica)
            h.runner(replica).run { context ->
                replica.runtime.announce()
                announcedJobs(h) shouldBe names
                ready(context)
                announcedJobs(h) shouldBe names
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `INV-AC-20638 announce called twice on one runtime emits one JobRegistered per job`(image: String) {
        JobsHarness(image).use { h ->
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            val names = twoJobs(replica)
            replica.runtime.announce()
            replica.runtime.announce()
            announcedJobs(h) shouldBe names
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `INV-AC-20638 a retention that differs from the row, or no row, fails the ready event and announces nothing`(
        image: String,
    ) {
        JobsHarness(image).use { h ->
            val longer =
                h.replica(
                    "svc-2",
                    MutableClock(Instant.now()),
                    extras = JobsHarness.ReplicaExtras(retention = Duration.ofDays(40)),
                )
            val names = twoJobs(longer)
            h.runner(longer).run { context ->
                assertThrows<IllegalStateException> { ready(context) }
                announcedJobs(h).shouldBeEmpty()
            }

            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            twoJobs(replica)
            h.owner.asPlatform { deleteFrom(retentionTable).execute() }
            h.runner(replica).run { context ->
                assertThrows<IllegalStateException> { ready(context) }
                announcedJobs(h).shouldBeEmpty()
            }

            h.writeRetention(JobRuntime.DEFAULT_RETENTION)
            replica.runtime.announce()
            announcedJobs(h) shouldBe names
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `INV-AC-20638 a context with no job runtime publishes the ready event without error and writes nothing`(
        image: String,
    ) {
        JobsHarness(image).use { h ->
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            twoJobs(replica)
            replica.withDataSourceBean(base(h)).run { context ->
                context.getBeansOfType(JobRuntime::class.java).size shouldBe 0
                ready(context)
                announcedJobs(h).shouldBeEmpty()
            }
        }
    }
}
