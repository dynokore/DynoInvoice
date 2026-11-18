package com.dynokore.platform.productruntime

import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventLogHead
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequired
import com.dynokore.platform.outboxandqueue.TopicHead
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.productruntime.feed.PolicyTopicReader
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.PolicyChanged
import com.dynokore.platform.serviceruntime.replica.PolicyFeedSink
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.LocalDate

/** INV-T-24768: the shared policy topic reader and its wiring (INV-AC-24766). */
class PolicyTopicReaderTest {
    private val topic = PlatformEventTypes.POLICY_CHANGED_TOPIC
    private val day = LocalDate.parse("2026-10-12")
    private val e1 = PolicyScope.entity(TENANT, "E1")
    private val e2 = PolicyScope.entity(TENANT, "E2")

    private class FakeLog(
        val records: MutableMap<String, MutableList<LogRecord>> = mutableMapOf(),
    ) : EventLogReader,
        EventLogHead {
        val afterArgs = mutableListOf<Pair<String, Long>>()

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> {
            afterArgs += topic to afterOffset
            return records[topic]
                .orEmpty()
                .filter { it.offset > afterOffset }
                .sortedBy { it.offset }
                .take(limit)
        }

        override fun head(
            topic: String,
            from: Long,
        ) = TopicHead(records[topic].orEmpty().maxOfOrNull { it.offset } ?: 0, 0)

        fun add(
            topic: String,
            record: LogRecord,
        ) {
            records.getOrPut(topic) { mutableListOf() }.add(record)
        }
    }

    private fun record(
        offset: Long,
        scope: String,
        vp: String,
        value: String,
    ) = LogRecord(
        offset,
        EnvelopeJson().write(
            envelope(
                PlatformEventTypes.POLICY_CHANGED,
                """{"scope":"$scope","vpId":"$vp","value":"$value","effectiveDate":"2026-10-01","source":"OVERRIDE"}""",
            ),
        ),
    )

    private val log = FakeLog()
    private val replica = spyk(PolicyReplica(CLOCK))
    private val incidents = mutableListOf<String>()

    private fun reader(
        source: EventLogReader = log,
        head: EventLogHead? = log,
        batch: Int = 100,
    ) = PolicyTopicReader(source, head, PolicyFeed(replica), replica, batch = batch, incident = { incidents += it })

    private fun seed() {
        log.add(topic, record(1, "ENTITY:E1", "VP-22", "X"))
        log.add(topic, record(2, "ENTITY:E1", "VP-21", "ON"))
        log.add(topic, record(3, "ENTITY:E2", "VP-21", "OFF"))
    }

    private fun value(
        scope: PolicyScope,
        vp: String = "VP-21",
    ) = replica.valueAt(scope, vp, day)?.value

    @Test
    fun `INV-AC-24766 first poll applies values, reports head and snapshot, second poll resumes after 3`() {
        seed()
        val r = reader()
        r.pollOnce()
        value(e1) shouldBe "\"ON\""
        value(e2) shouldBe "\"OFF\""
        replica.appliedOffset(topic) shouldBe 3L
        (replica.lag(topic) is Lag.Measured) shouldBe true
        verifyOrder {
            replica.observeHead(topic, 3L)
            replica.onSnapshotComplete(topic, 3L)
        }
        verify(exactly = 1) { replica.onSnapshotComplete(any(), any()) }
        verify(exactly = 0) { replica.onDisconnected(any()) }
        r.pollOnce()
        log.afterArgs shouldBe listOf(topic to 0L, topic to 3L)
        replica.appliedOffset(topic) shouldBe 3L
    }

    @Test
    fun `INV-AC-24766 snapshot is not reported before the head is reached`() {
        seed()
        val r = reader(batch = 1)
        r.pollOnce()
        replica.appliedOffset(topic) shouldBe 1L
        replica.lag(topic) shouldBe Lag.Unknown
        r.pollOnce()
        r.pollOnce()
        replica.appliedOffset(topic) shouldBe 3L
        (replica.lag(topic) is Lag.Measured) shouldBe true
    }

    @Test
    fun `INV-AC-24766 a new record at offset 4 is applied and a second delivery changes nothing`() {
        seed()
        val r = reader()
        r.pollOnce()
        log.add(topic, record(4, "ENTITY:E1", "VP-21", "OFF"))
        r.pollOnce()
        replica.appliedOffset(topic) shouldBe 4L
        value(e1) shouldBe "\"OFF\""
        log.records
            .getValue(topic)
            .filter { it.offset <= 3 }
            .forEach { PolicyFeed(replica).accept(topic, it) }
        value(e1) shouldBe "\"OFF\""
        value(e2) shouldBe "\"OFF\""
        replica.appliedOffset(topic) shouldBe 4L
    }

    @Test
    fun `INV-AC-24766 resumes from the position the replica holds after a restart of the reader`() {
        seed()
        reader().pollOnce()
        log.afterArgs.clear()
        reader().pollOnce()
        log.afterArgs shouldBe listOf(topic to 3L)
    }

    @Test
    fun `INV-AC-24766 RebuildRequired applies nothing, disconnects the topic and is not read again`() {
        var calls = 0
        val purged =
            object : EventLogReader {
                override fun read(
                    topic: String,
                    afterOffset: Long,
                    limit: Int,
                ): List<LogRecord> {
                    calls++
                    throw RebuildRequired(topic, 1, 5)
                }
            }
        replica.observeHead(topic, 0)
        replica.onSnapshotComplete(topic, 0)
        (replica.lag(topic) is Lag.Measured) shouldBe true
        val r = reader(source = purged)
        r.pollOnce()
        r.pollOnce()
        r.pollOnce()
        calls shouldBe 1
        r.isStopped shouldBe true
        replica.appliedOffset(topic) shouldBe 0L
        value(e1) shouldBe null
        verify(exactly = 1) { replica.onDisconnected(topic) }
        verify(exactly = 0) { replica.accept(any()) }
        incidents.single() shouldContain topic
    }

    @Test
    fun `INV-AC-24766 a poison record is skipped once with an incident naming topic and offset`() {
        seed()
        val r = reader()
        r.pollOnce()
        log.add(topic, LogRecord(5, """{"not":"an envelope"}"""))
        r.pollOnce()
        r.pollOnce()
        incidents.size shouldBe 1
        incidents.single() shouldContain topic
        incidents.single() shouldContain "offset 5"
        log.afterArgs.last() shouldBe (topic to 5L)
        replica.appliedOffset(topic) shouldBe 3L
        value(e1) shouldBe "\"ON\""
    }

    @Test
    fun `INV-AC-24766 a failure in accept does not move the position and the next poll retries`() {
        seed()
        var fail = true
        val flaky =
            object : PolicyFeedSink by replica {
                override fun accept(event: PolicyChanged) {
                    if (fail && event.offset == 2L) error("db down")
                    replica.accept(event)
                }
            }
        val r = PolicyTopicReader(log, log, PolicyFeed(flaky), replica, incident = { incidents += it })
        r.pollOnce()
        replica.appliedOffset(topic) shouldBe 1L
        fail = false
        r.pollOnce()
        log.afterArgs shouldBe listOf(topic to 0L, topic to 1L)
        replica.appliedOffset(topic) shouldBe 3L
        value(e1) shouldBe "\"ON\""
        r.isStopped shouldBe false
    }

    @Test
    fun `INV-AC-24766 a record of another topic is never read`() {
        seed()
        log.add("gst.platform.policy-changed.v2", record(9, "ENTITY:E1", "VP-21", "ZZZ"))
        reader().pollOnce()
        log.afterArgs.map { it.first }.toSet() shouldBe setOf(topic)
        value(e1) shouldBe "\"ON\""
    }

    // ---- wiring (P109, TPL-18) ----

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PolicyReaderWiring::class.java))
            .withBean(PolicyReplica::class.java, { replica })
            .withBean(PolicyFeed::class.java, { PolicyFeed(replica) })
            .withPropertyValues("product-runtime.policy-reader.poll-interval=PT10M")

    @Test
    fun `INV-AC-24766 wiring has the reader bean when an EventLogReader and a PolicyReplica exist`() {
        runner.withBean(EventLogReader::class.java, { log }).run {
            (it.getBeansOfType(PolicyTopicReader::class.java).size) shouldBe 1
            (it.getBeansOfType(PolicyFeed::class.java).size) shouldBe 1
            it.getBean(PolicyTopicReaderRunner::class.java).isRunning shouldBe true
        }
    }

    @Test
    fun `INV-AC-24766 wiring has no reader when the property is false`() {
        runner
            .withBean(EventLogReader::class.java, { log })
            .withPropertyValues("product-runtime.policy-reader.enabled=false")
            .run { it.getBeansOfType(PolicyTopicReader::class.java).size shouldBe 0 }
    }

    @Test
    fun `INV-AC-24766 wiring has no reader without an EventLogReader`() {
        runner.run { it.getBeansOfType(PolicyTopicReader::class.java).size shouldBe 0 }
    }
}
