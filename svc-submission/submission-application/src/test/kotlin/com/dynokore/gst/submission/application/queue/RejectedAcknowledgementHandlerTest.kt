package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT_UNCONFIRMED
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The REJECTED outcome of AcknowledgementReceived over fakes: router, decision, writes, idempotence (INV-AC-24282). */
class RejectedAcknowledgementHandlerTest {
    private val now = Instant.parse("2026-11-18T10:00:00Z")
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000024"))
    private val doc = "D1"
    private val calls = mutableListOf<String>()
    private val facts = mutableListOf<SubmissionFailure>()
    private val recorded = mutableListOf<String>()
    private val states = sortedMapOf<Int, LifecycleState>(1 to SENT)
    private var latestAttempt: Int? = 2
    private var data =
        RejectedAcknowledgementData(
            doc,
            1,
            listOf(RejectionReason("SUB-004-R1", null, "m1"), RejectionReason("X-9", null, "m2")),
        )
    private val delegated = mutableListOf<String>()

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
            when (m.name.substringBefore('-')) {
                "lockForUpdate" -> {
                    val version = a[2] as Int
                    calls += "lockForUpdate:$version"
                    states[version]?.let {
                        LockedSubmission(it, null, null, 1, EventFacts("E", null), OutcomeFacts(null, 0, null))
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

    private val codes =
        object : FailureCodes {
            override fun record(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                attemptNumber: Int,
                code: String,
            ) {
                calls += "write:code"
                recorded += "$docUuid:$version:$attemptNumber:$code"
            }

            override fun lastOf(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): String? = error("not used")
        }

    private val catalogue =
        ErrorCatalogue { if (it == "SUB-004-R1") CatalogueEntry("SUB-004-R1", "OPERATIONS", "MANUAL") else null }

    private val signals =
        FailureSignals {
            calls += "write:stage"
            facts += it
        }

    private val rejectedHandler =
        RejectedAcknowledgementHandler(
            tenants,
            { data },
            inFlight,
            store,
            { _, _, _ -> latestAttempt },
            codes,
            catalogue,
            signals,
        )

    private fun tag(name: String) =
        object : EventHandler {
            override fun handle(event: Envelope): HandleOutcome {
                delegated += name
                return HandleOutcome.APPLIED
            }
        }

    private fun router(outcome: String?) =
        AcknowledgementOutcomeRouter(
            { outcome ?: AcknowledgementReceivedData.OUTCOME_ACCEPTED },
            tag("accepted"),
            tag("rejected"),
        )

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
    fun `INV-AC-24282 a rejection moves SENT to REJECTED, records the code, stages one failure`() {
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        states[1] shouldBe REJECTED
        recorded shouldContainExactly listOf("D1:1:2:SUB-004-R1")
        facts shouldContainExactly
            listOf(SubmissionFailure("D1", 1, "SUB-004-R1", "MANUAL", "SUB-004-R1", "OPERATIONS", "MANUAL"))
    }

    @Test
    fun `INV-AC-24282 SENT_UNCONFIRMED is rejected too`() {
        states[1] = SENT_UNCONFIRMED
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        states[1] shouldBe REJECTED
    }

    @Test
    fun `INV-AC-24282 the write follows the decision and the lock`() {
        rejectedHandler.handle(envelope())
        calls shouldContainExactly
            listOf("lockVersions", "lockForUpdate:1", "write:setState:1:REJECTED", "write:code", "write:stage")
    }

    @Test
    fun `INV-AC-24282 a repeat changes nothing and stages nothing`() {
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        calls.clear()
        facts.clear()
        recorded.clear()
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.IGNORED
        writes().shouldBeEmpty()
        facts.shouldBeEmpty()
        recorded.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-24282 an ACKNOWLEDGED version is ignored with no write`() {
        states[1] = ACKNOWLEDGED
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.IGNORED
        writes().shouldBeEmpty()
        facts.shouldBeEmpty()
        states[1] shouldBe ACKNOWLEDGED
    }

    @Test
    fun `INV-AC-24282 an unknown version is ignored with no write`() {
        data = data.copy(version = 7)
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.IGNORED
        writes().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-24282 a rejection with no reason is refused before any write`() {
        data = data.copy(reasons = emptyList())
        shouldThrow<IllegalArgumentException> { rejectedHandler.handle(envelope()) }
        calls.shouldBeEmpty()
        states[1] shouldBe SENT
    }

    @Test
    fun `INV-AC-24282 a code absent from the catalogue stages null members and TERMINAL`() {
        data = data.copy(reasons = listOf(RejectionReason("X-9", null, "m2")))
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        facts shouldContainExactly listOf(SubmissionFailure("D1", 1, "X-9", "TERMINAL", null, null, null))
    }

    @Test
    fun `INV-AC-24282 a version with no attempt records no code but is rejected and announced`() {
        latestAttempt = null
        rejectedHandler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        recorded.shouldBeEmpty()
        states[1] shouldBe REJECTED
        facts.size shouldBe 1
    }

    @Test
    fun `INV-AC-24282 the router sends REJECTED to the rejected handler only`() {
        router("REJECTED").handle(envelope())
        delegated shouldContainExactly listOf("rejected")
    }

    @Test
    fun `INV-AC-24282 the router sends ACCEPTED or an absent outcome to the acknowledgement handler`() {
        router("ACCEPTED").handle(envelope())
        router(null).handle(envelope())
        delegated shouldContainExactly listOf("accepted", "accepted")
    }

    @Test
    fun `INV-AC-24282 the router refuses an unknown outcome and delegates nothing`() {
        shouldThrow<IllegalArgumentException> { router("MAYBE").handle(envelope()) }
        delegated.shouldBeEmpty()
    }
}
