package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.reconciliation.application.workpaper.export.ListRelaxedControls
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxedControlsSource
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxedControlsUnavailable
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-24395 through the production wiring of the policy replica (TPL-18, P109): the beans of
 * [PolicyReplicaConfiguration] over a real [PolicyReplica], the catalogue bean of the composed service, and a
 * fake log of PolicyChanged records built from the members of policy-changed-payload.schema.json. No database is used.
 */
class PolicyReplicaRelaxedControlsTest {
    private val topic = "gst.platform.policy-changed.v1"
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val start = Instant.parse("2026-05-01T00:00:00Z")
    private val on = LocalDate.parse("2026-04-30")

    private class MovableClock(
        private var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }

    private inner class Wiring(
        val clock: MovableClock,
        val replica: PolicyReplica,
        val handler: PolicyChangedHandler,
        val source: RelaxedControlsSource,
        val view: com.dynokore.gst.reconciliation.adapters.policy.PolicyReplicaView,
    )

    private fun wiring(catalogue: VariationPointCatalogue): Wiring {
        val clock = MovableClock(start)
        val replica = PolicyReplica(clock, setOf(topic))
        val configuration = PolicyReplicaConfiguration()
        val view =
            configuration.policyReplicaView(
                replica,
                catalogue,
                MockEnvironment().withProperty(PolicyReplicaConfiguration.MAX_LAG_PROPERTY, "PT10M"),
            )
        return Wiring(
            clock,
            replica,
            PolicyChangedHandler(PolicyFeed(replica)),
            configuration.relaxedControlsSource(view),
            view,
        )
    }

    private fun payload(
        vpId: String,
        entity: String,
        ack: String?,
    ): String =
        buildString {
            append("""{"scope":"ENTITY:$entity","vpId":"$vpId","value":["OPTION"],"effectiveDate":"2026-04-01",""")
            append(""""source":"OVERRIDE"""")
            if (ack != null) append(""","relaxationAcknowledgementReference":"$ack"""")
            append("}")
        }

    private fun envelope(
        data: String,
        entity: String = "E1",
    ) = Envelope(
        EventId(UUID.randomUUID().toString()),
        "/gst/access-svc",
        "com.dynokore.gst.platform.PolicyChanged.v1",
        "policy-1",
        start,
        t1,
        EntityRef(entity),
        null,
        null,
        null,
        "access-svc-0",
        CorrelationId("corr-1"),
        CausationId("cause-1"),
        data,
    )

    private fun deliver(
        w: Wiring,
        offset: Long,
        data: String,
    ): HandleOutcome {
        val envelope = envelope(data)
        return w.handler.handle(envelope, LogRecord(offset, EnvelopeJson().write(envelope)))
    }

    private val r1 = payload("VP-40", "E1", "ACK-40")
    private val r2 = payload("VP-41", "E1", null)

    private fun withCatalogue(
        dir: Path,
        body: (VariationPointCatalogue) -> Unit,
    ) {
        ReconciliationContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            body(context.getBean(VariationPointCatalogue::class.java))
        }
    }

    private fun listed(
        w: Wiring,
        entity: String,
    ) = w.source.relaxed(t1.toString(), entity, on).map { it.code to it.description }

    @Test
    fun `INV-AC-24395 before any record the view is not current and the use case throws RelaxedControlsUnavailable`(
        @TempDir dir: Path,
    ) = withCatalogue(dir) { catalogue ->
        val w = wiring(catalogue)
        w.replica.lag() shouldBe Lag.Unknown
        w.view.current() shouldBe false
        shouldThrow<RelaxedControlsUnavailable> { listed(w, "E1") }
        deliver(w, 1, r1) shouldBe HandleOutcome.APPLIED
        w.view.current() shouldBe false
    }

    @Test
    fun `INV-AC-24395 after the snapshot and R1 and R2 E1 lists VP-40 with its catalogue title and E2 none`(
        @TempDir dir: Path,
    ) = withCatalogue(dir) { catalogue ->
        val w = wiring(catalogue)
        w.replica.onSnapshotComplete(topic, 0)
        deliver(w, 1, r1) shouldBe HandleOutcome.APPLIED
        deliver(w, 2, r2) shouldBe HandleOutcome.APPLIED
        w.view.current() shouldBe true
        w.view.controls().map { it.code } shouldBe listOf("VP-40")
        listed(w, "E1") shouldBe listOf("VP-40" to checkNotNull(VariationPoint.fromId("VP-40")).title)
        listed(w, "E2") shouldBe emptyList()
        w.replica
            .valueAt(PolicyScope.entity(t1, "E1"), "VP-40", on)
            ?.relaxationAckRef shouldBe "ACK-40"
        w.replica.valueAt(PolicyScope.entity(t1, "E2"), "VP-40", on) shouldBe null
        w.replica.valueAt(PolicyScope.entity(t1, "E1"), "VP-41", on)?.relaxationAckRef shouldBe null
    }

    @Test
    fun `INV-AC-24395 a lag of PT11M is above PT10M so the view is not current and the use case throws`(
        @TempDir dir: Path,
    ) = withCatalogue(dir) { catalogue ->
        val w = wiring(catalogue)
        w.replica.onSnapshotComplete(topic, 0)
        deliver(w, 1, r1)
        w.view.current() shouldBe true
        w.replica.observeHead(topic, 50)
        w.clock.advance(Duration.parse("PT10M"))
        w.replica.lag() shouldBe Lag.Measured(Duration.parse("PT10M"))
        w.view.current() shouldBe true
        w.clock.advance(Duration.parse("PT1M"))
        w.replica.lag() shouldBe Lag.Measured(Duration.parse("PT11M"))
        w.view.current() shouldBe false
        shouldThrow<RelaxedControlsUnavailable> { listed(w, "E1") }
    }

    @Test
    fun `INV-AC-24395 a contract-violating record is not applied and the handler throws ContractViolation`(
        @TempDir dir: Path,
    ) = withCatalogue(dir) { catalogue ->
        val w = wiring(catalogue)
        w.replica.onSnapshotComplete(topic, 0)
        deliver(w, 1, r1)
        val invalid = """{"scope":"ENTITY:E1","effectiveDate":"2026-04-01","source":"OVERRIDE"}"""
        shouldThrow<ContractViolation> { deliver(w, 2, invalid) }
        val badSource = payload("VP-40", "E1", "ACK-NEW").replace("OVERRIDE", "NOT_A_SOURCE")
        shouldThrow<ContractViolation> { deliver(w, 3, badSource) }
        w.replica.valueAt(PolicyScope.entity(t1, "E1"), "VP-40", on)?.relaxationAckRef shouldBe "ACK-40"
        w.replica.appliedOffset(topic) shouldBe 1L
    }

    @Test
    fun `INV-AC-24395 one-argument handle throws IllegalStateException and a replayed record ContractViolation`(
        @TempDir dir: Path,
    ) = withCatalogue(dir) { catalogue ->
        val w = wiring(catalogue)
        w.replica.onSnapshotComplete(topic, 0)
        val envelope = envelope(r1)
        shouldThrow<IllegalStateException> { w.handler.handle(envelope) }
        shouldThrow<ContractViolation> {
            w.handler.handle(envelope, LogRecord(LogRecord.REPLAY_OFFSET, EnvelopeJson().write(envelope)))
        }
        w.replica.valueAt(PolicyScope.entity(t1, "E1"), "VP-40", on) shouldBe null
        w.replica.appliedOffset(topic) shouldBe 0L
    }

    @Test
    fun `INV-AC-24395 the registry holds one new subscription on the PolicyChanged topic and its catalogue entry`(
        @TempDir dir: Path,
    ) {
        PolicyReplicaConfiguration.POLICY_CHANGED shouldBe
            ConsumerSubscription(
                "reconciliation-svc-policy-changed",
                "gst.platform.policy-changed.v1",
                "reconciliation",
            )
        ReconciliationContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val consumers = context.getBeansOfType(InboxConsumer::class.java)
            consumers.keys.sorted() shouldBe
                listOf(
                    "canonicalVersionCreatedConsumer",
                    "periodReassignedConsumer",
                    "policyChangedConsumer",
                    "submissionAcknowledgedConsumer",
                )
            val entries = context.getBean(EventCatalogue::class.java).entries().toList()
            entries.filter { it.topic == topic }.map { it.eventType } shouldBe
                listOf("com.dynokore.gst.platform.PolicyChanged.v1")
            context
                .getBeansOfType(RelaxedControlsSource::class.java)
                .values
                .single()
                .javaClass shouldBe
                ListRelaxedControls::class.java
        }
    }

    @Test
    fun `INV-AC-24395 the lag bound is the property max-lag, no default in code, and start fails without it`(
        @TempDir dir: Path,
    ) {
        ReconciliationContext
            .offline(dir)
            .withPropertyValues("reconciliation.policy-replica.max-lag=")
            .run { context -> (context.startupFailure != null) shouldBe true }
        ReconciliationContext
            .offline(dir)
            .withPropertyValues("reconciliation.policy-replica.max-lag=PT10M")
            .run { context -> context.startupFailure shouldBe null }
    }
}
