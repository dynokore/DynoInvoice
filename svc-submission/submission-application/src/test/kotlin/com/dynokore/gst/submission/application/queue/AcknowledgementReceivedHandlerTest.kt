package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SUPERSEDED
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The acknowledgement consumer over fakes: decision, order of reads and writes, idempotence (INV-AC-24270). */
class AcknowledgementReceivedHandlerTest {
    private val now = Instant.parse("2026-11-18T10:00:00Z")
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000024"))
    private val doc = "D1"
    private val calls = mutableListOf<String>()
    private val facts = mutableListOf<AcknowledgedFact>()
    private val states = sortedMapOf<Int, LifecycleState>()
    private val ids = mutableMapOf<Int, String>()
    private var next = AcknowledgementReceivedData(doc, 1, "ACK-1")

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val inFlight =
        object : InFlightStore {
            override fun lockVersions(
                tenant: TenantId,
                docUuid: String,
            ): List<VersionState> {
                calls += "lockVersions"
                return states.map { VersionState(it.key, it.value) }
            }

            override fun waitingDocUuids(
                tenant: TenantId,
                limit: Int,
            ): List<String> = error("not used")
        }

    private val store: SubmissionStore =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SubmissionStore::class.java),
        ) { _, m, a ->
            // a method taking the TenantId value class carries a mangled name suffix after '-'
            when (m.name.substringBefore('-')) {
                "lockForUpdate" -> {
                    val version = a[2] as Int
                    calls += "lockForUpdate:$version"
                    states[version]?.let {
                        LockedSubmission(it, null, null, 1, EventFacts("E", null), OutcomeFacts(ids[version], 0, null))
                    }
                }

                "setState" -> {
                    calls += "write:setState:${a[2]}:${a[3]}"
                    states[a[2] as Int] = a[3] as LifecycleState
                    null
                }

                else -> {
                    error("unexpected ${m.name}")
                }
            }
        } as SubmissionStore

    private val recorder =
        AcknowledgementRecorder { _, _, version, id, at ->
            calls += "write:record:$version:$id:$at"
            ids[version] = id
        }

    private val publisher =
        AcknowledgedPublisher {
            calls += "write:stage:${it.version}"
            facts += it
        }

    private val handler =
        AcknowledgementReceivedHandler(
            tenants,
            { next },
            inFlight,
            store,
            recorder,
            publisher,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun deliver(
        version: Int,
        id: String,
        outcome: String = "ACCEPTED",
    ): HandleOutcome {
        next = AcknowledgementReceivedData(doc, version, id, outcome)
        return handler.handle(envelope())
    }

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/transport-svc",
            type = "com.dynokore.gst.transport.AcknowledgementReceived.v1",
            subject = doc,
            time = now,
            tenant = tenant,
            entity = EntityRef("E"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "transport-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    private fun writes() = calls.filter { it.startsWith("write:") }

    @Test
    fun `INV-AC-24270 version 1 is acknowledged once with the id and the clock instant and one fact`() {
        states[1] = SENT
        deliver(1, "ACK-1") shouldBe HandleOutcome.APPLIED
        states[1] shouldBe ACKNOWLEDGED
        ids[1] shouldBe "ACK-1"
        calls.contains("write:record:1:ACK-1:$now") shouldBe true
        facts.size shouldBe 1
        val fact = facts.single()
        listOf(fact.docUuid, fact.version, fact.acknowledgementId, fact.timestamp) shouldBe listOf(doc, 1, "ACK-1", now)
    }

    @Test
    fun `INV-AC-24270 a repeat of the same event is ignored with no write and no fact`() {
        states[1] = SENT
        deliver(1, "ACK-1")
        calls.clear()
        deliver(1, "ACK-1") shouldBe HandleOutcome.IGNORED
        writes().shouldBeEmpty()
        facts.size shouldBe 1
    }

    @Test
    fun `INV-AC-24270 version 2 acknowledged supersedes the older acknowledged version 1`() {
        states[1] = ACKNOWLEDGED
        ids[1] = "ACK-1"
        states[2] = SENT
        deliver(2, "ACK-2") shouldBe HandleOutcome.APPLIED
        states[2] shouldBe ACKNOWLEDGED
        states[1] shouldBe SUPERSEDED
        ids[2] shouldBe "ACK-2"
        facts.map { it.version } shouldBe listOf(2)
    }

    @Test
    fun `INV-AC-24270 a blank id is ignored with no write and no fact`() {
        states[1] = SENT
        deliver(1, "  ") shouldBe HandleOutcome.IGNORED
        writes().shouldBeEmpty()
        facts.shouldBeEmpty()
        states[1] shouldBe SENT
    }

    @Test
    fun `INV-AC-24270 an unknown version is ignored with no write and no fact`() {
        states[1] = SENT
        deliver(5, "ACK-5") shouldBe HandleOutcome.IGNORED
        writes().shouldBeEmpty()
        facts.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-24270 a different id for an acknowledged version is ignored with no write and no fact`() {
        states[1] = SENT
        deliver(1, "ACK-1")
        calls.clear()
        deliver(1, "ACK-2") shouldBe HandleOutcome.IGNORED
        writes().shouldBeEmpty()
        ids[1] shouldBe "ACK-1"
        facts.size shouldBe 1
    }

    @Test
    fun `INV-AC-24270 a REJECTED outcome throws and writes nothing`() {
        states[1] = SENT
        shouldThrow<UnsupportedAcknowledgementOutcome> { deliver(1, "ACK-1", "REJECTED") }
        writes().shouldBeEmpty()
        facts.shouldBeEmpty()
        states[1] shouldBe SENT
    }

    @Test
    fun `INV-AC-24270 the lock precedes every read of an id and every write follows the decision`() {
        states[1] = ACKNOWLEDGED
        ids[1] = "ACK-1"
        states[2] = SENT
        deliver(2, "ACK-2")
        calls.first() shouldBe "lockVersions"
        val firstRead = calls.indexOfFirst { it.startsWith("lockForUpdate") }
        val firstWrite = calls.indexOfFirst { it.startsWith("write:") }
        (calls.indexOf("lockVersions") < firstRead) shouldBe true
        (firstRead < firstWrite) shouldBe true
        calls.any { it.startsWith("lockForUpdate") } shouldBe true
    }

    @Test
    fun `INV-AC-24270 a from-state mismatch throws and leaves no write`() {
        states[1] = SENT
        val skewedLocks =
            object : InFlightStore by inFlight {
                override fun lockVersions(
                    tenant: TenantId,
                    docUuid: String,
                ): List<VersionState> = listOf(VersionState(1, SENT))
            }
        val skewed =
            AcknowledgementReceivedHandler(
                tenants,
                { next },
                skewedLocks,
                store,
                recorder,
                publisher,
                Clock.fixed(now, ZoneOffset.UTC),
            )
        states[1] = LifecycleState.REJECTED // the locked row differs from what lockVersions reported
        next = AcknowledgementReceivedData(doc, 1, "ACK-1")
        shouldThrow<IllegalStateException> { skewed.handle(envelope()) }
        writes().shouldBeEmpty()
        facts.shouldBeEmpty()
    }
}
