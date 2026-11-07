package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import com.dynokore.gst.transport.application.PollCursorStore
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.platform.serviceruntime.jobs.FireResult
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Makes the cursor store of the running context fail on its next write, as an instance that stops would. */
internal object CursorFault {
    val failNextWrite = AtomicBoolean(false)
}

// not @Configuration: component scanning of the test classpath would put it into every other test's context
internal class CursorFaultConfig {
    @Bean
    fun cursorFault(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is PollCursorStore) {
                    object : PollCursorStore by bean {
                        override fun write(
                            adapter: AdapterId,
                            cursor: String?,
                        ) {
                            check(
                                !CursorFault.failNextWrite.getAndSet(false),
                            ) { "stopped before the cursor was stored" }
                            bean.write(adapter, cursor)
                        }
                    }
                } else {
                    bean
                }
        }
}

/** One clock for both contexts, so a test moves from one tick to the next without waiting for the leases. */
internal object TestClock : java.time.Clock() {
    @Volatile
    var now: Instant = Instant.parse("2030-01-01T10:00:00Z")

    override fun instant(): Instant = now

    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
}

// not @Configuration: component scanning of the test classpath would put it into every other test's context
internal class TestClockConfig {
    @Bean
    fun testClock(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any = if (bean is java.time.Clock) TestClock else bean
        }
}

/**
 * Leader-elected polling of the AP notifications (INV-REQ-586, INV-Q-9684): two application contexts over one
 * database (the ShedLock election of service-runtime) and the certification-kit AP simulator.
 */
class ApPollingDbTest {
    @BeforeEach
    fun resetSimulator() {
        ApSimulator.reset()
        CursorFault.failNextWrite.set(false)
    }

    private val tenant = UUID.randomUUID()
    private val adapter = AdapterId("p-${UUID.randomUUID().toString().take(ID_LENGTH)}")
    private val t0: Instant = Instant.parse("2030-01-01T10:00:00Z")

    private fun props(
        mode: String,
        vararg more: String,
    ) = arrayOf(
        "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}",
        "transport.ap.adapters[0].id=${adapter.value}",
        "transport.ap.adapters[0].path=primary",
        "transport.ap.adapters[0].notifications=$mode",
        *more,
    )

    private fun polling(vararg more: String) = props("POLLING", "transport.ap.poll-interval=PT1H", *more)

    private fun job() = ApPollJobs.jobName(adapter)

    private fun ApplicationContext.fire(at: Instant): FireResult? =
        run {
            TestClock.now = at
            getBean(JobRuntime::class.java).fire(job(), at)[JobTarget.Whole]
        }

    private fun ApplicationContext.polls() = getBean(ApPollLog::class.java).entries.size

    private fun java.sql.Connection.count(
        table: String,
        where: String,
    ): Int =
        createStatement().use { s ->
            s.executeQuery("select count(*) from $table where $where").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun acknowledgementEvents(): Int =
        TransportDb.superuser().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from transport.outbox where type like '%AcknowledgementReceived%' " +
                            "and tenant_id = '$tenant'",
                    ).use {
                        it.next()
                        it.getInt(1)
                    }
            }
        }

    private fun cursorRows(): Int =
        TransportDb.superuser().use {
            it.count("transport.ap_poll_cursor", "adapter_id = '${adapter.value}'")
        }

    /** Submits one document through the adapter so the simulator holds its notifications and the owner is known. */
    private fun Harness.submitOne() {
        assign(tenant, "E-001", adapter)
        submitPayload(tenant, "E-001", DocRef(UUID.randomUUID(), 1))
    }

    /** Two contexts of two instances over one database; [block] gets both. */
    private fun twoInstances(
        dir: Path,
        other: Path,
        vararg overrides: String,
        block: (Harness, ApplicationContext) -> Unit,
    ) {
        writeRetentionRow()
        TransportContext
            .online(dir, *overrides, "gst.instance-id=transport-svc-0")
            .withUserConfiguration(CursorFaultConfig::class.java, TestClockConfig::class.java)
            .run { first ->
                check(first.startupFailure == null) { "first context failed: ${first.startupFailure}" }
                TransportContext
                    .online(other, *overrides, "gst.instance-id=transport-svc-1")
                    .withUserConfiguration(TestClockConfig::class.java)
                    .run { second ->
                        check(second.startupFailure == null) { "second context failed: ${second.startupFailure}" }
                        block(Harness(first, adapter, AdapterId("unused")), second)
                    }
            }
    }

    @Test
    fun `INV-AC-9489 two instances, one database, one poll per firing and one event for a notification seen twice`(
        @TempDir dir: Path,
        @TempDir other: Path,
    ) = twoInstances(dir, other, *polling()) { a, b ->
        a.submitOne()
        // firing 1: both instances fire the same tick
        val first = listOf(a.context.fire(t0), b.fire(t0))
        first.count { it == FireResult.RAN } shouldBe 1
        (a.context.polls() + b.polls()) shouldBe 1
        ledgerRows() shouldBe 2 // DELIVERY_STATUS and ACKNOWLEDGEMENT of the one submission
        acknowledgementEvents() shouldBe 1

        // the AP delivers the same notification again; firing 2 is the next tick
        ApSimulator.control("/ap/notifications/redeliver", "{}")
        val second = listOf(b.fire(t0.plus(Duration.ofHours(1))), a.context.fire(t0.plus(Duration.ofHours(1))))
        second.count { it == FireResult.RAN } shouldBe 1
        (a.context.polls() + b.polls()) shouldBe 2
        ledgerRows() shouldBe 2
        acknowledgementEvents() shouldBe 1
    }

    @Test
    fun `INV-AC-1780 a notification seen in two polls gives one ledger row and one event`(
        @TempDir dir: Path,
        @TempDir other: Path,
    ) = twoInstances(dir, other, *polling()) { a, b ->
        a.submitOne()
        a.context.fire(t0) shouldBe FireResult.RAN
        val ledgerAfterFirst = ledgerRows()
        ApSimulator.control("/ap/notifications/redeliver", "{}")
        b.fire(t0.plus(Duration.ofHours(1))) shouldBe FireResult.RAN
        ledgerRows() shouldBe ledgerAfterFirst
        acknowledgementEvents() shouldBe 1
    }

    @Test
    fun `INV-AC-1780 an instance that stops after translation and before the cursor is stored adds no second event`(
        @TempDir dir: Path,
        @TempDir other: Path,
    ) = twoInstances(dir, other, *polling()) { a, b ->
        a.submitOne()
        CursorFault.failNextWrite.set(true)
        a.context.fire(t0) // the run fails at the cursor store: recorded FAILED, cursor not stored
        cursorRows() shouldBe 0
        acknowledgementEvents() shouldBe 1
        b.fire(t0.plus(Duration.ofHours(1))) shouldBe FireResult.RAN
        cursorRows() shouldBe 1
        ledgerRows() shouldBe 2
        acknowledgementEvents() shouldBe 1
    }

    @Test
    fun `INV-AC-1780 an adapter in CALLBACK mode is never polled`(
        @TempDir dir: Path,
        @TempDir other: Path,
    ) = twoInstances(dir, other, *props("CALLBACK", "transport.ap.poll-interval=PT1H")) { a, b ->
        a.submitOne()
        val calls = ApSimulator.callCount()
        listOf(a.context, b).forEach { c ->
            c.getBean(ApPollJobs::class.java).jobNames.shouldBeEmpty()
            c.getBean(JobRuntime::class.java).registered().none { it.name.startsWith("ap-poll-") } shouldBe true
            shouldThrow<IllegalArgumentException> { c.fire(t0) }
        }
        ApSimulator.callCount() shouldBe calls
        cursorRows() shouldBe 0
    }

    @Test
    fun `INV-AC-1780 the default mode is CALLBACK and poll-interval is then not required`(
        @TempDir dir: Path,
    ) {
        TransportContext.online(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}").run { c ->
            check(c.startupFailure == null) { "context failed to start: ${c.startupFailure}" }
            c.getBean(ApPollJobs::class.java).jobNames.shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-1780 poll-interval missing with a POLLING adapter fails start-up naming it`(
        @TempDir dir: Path,
    ) {
        TransportContext.online(dir, *props("POLLING")).run { c ->
            checkNotNull(c.startupFailure) { "the context started without poll-interval" }
            generateSequence(c.startupFailure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
                "poll-interval"
        }
    }

    @Test
    fun `INV-AC-1780 the poll job is a platform job of transport-svc and its name carries the adapter id`(
        @TempDir dir: Path,
        @TempDir other: Path,
    ) = twoInstances(dir, other, *polling()) { a, _ ->
        val def =
            a.context
                .getBean(JobRuntime::class.java)
                .registered()
                .single { it.name == job() }
        def.ownerService shouldBe "transport-svc"
        def.entityScope.name shouldBe "PLATFORM"
        job() shouldBe "ap-poll-${adapter.value}"
    }

    /** The row the migration runner writes at install (INV-Q-4346); this harness applies only the Flyway files. */
    private fun writeRetentionRow() =
        TransportDb.superuser().use { c ->
            c.createStatement().use {
                it.execute(
                    "insert into transport.job_retention (id, job_window) values (true, interval '2592000 seconds') " +
                        "on conflict do nothing",
                )
            }
        }

    private fun ledgerRows(): Int =
        TransportDb.superuser().use {
            it.count("transport.ap_notification_ledger", "adapter_id = '${adapter.value}'")
        }

    private companion object {
        const val ID_LENGTH = 12
    }
}
