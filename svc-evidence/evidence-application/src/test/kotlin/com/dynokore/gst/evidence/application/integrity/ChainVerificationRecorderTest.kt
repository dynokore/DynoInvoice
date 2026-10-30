package com.dynokore.gst.evidence.application.integrity

import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.domain.chain.AuditChainKey
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.gst.evidence.domain.digest.periodDigest
import com.dynokore.gst.evidence.domain.integrity.FailureKind
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.platform.evidencestore.ChainVerificationResult
import com.dynokore.platform.evidencestore.ContentHash
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ChainVerificationRecorderTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val auditPeriod = AuditChainKey.PREFIX + "2026"
    private val period = "2026-Q1"
    private val first = Instant.parse("2026-02-01T00:00:00Z")
    private val last = Instant.parse("2026-02-03T00:00:00Z")
    private val runAt = Instant.parse("2026-05-01T00:00:00.123456789Z")
    private val tenantWide = "00000000-0000-0000-0000-000000000000"
    private val domainType = ChainVerificationRecorder.EVENT_TYPE
    private val platformType = ChainVerificationRecorder.TENANT_WIDE_EVENT_TYPE

    /** In-memory ports; one chain of [kind] of length 3 for the entity and period, broken at sequence 1 when asked. */
    private inner class Memory(
        val entity: String,
        val chainPeriod: String,
        val kind: RecordKind,
        val broken: Boolean = false,
        val digest: PeriodDigestEntry? = null,
        val copyMatches: Boolean = true,
    ) {
        val rows = mutableListOf<IntegrityFailureRow>()
        val runs = mutableListOf<IntegrityRunRow>()
        val events = mutableListOf<OutboundEvent>()
        val contexts = mutableListOf<EventContext>()
        val order = mutableListOf<String>()

        private val reads =
            object : ChainReads {
                override fun periods(
                    tenant: TenantId,
                    entityRef: String,
                ) = listOf(chainPeriod)

                override fun kinds(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                ) = listOf(kind)

                override fun facts(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                    kind: RecordKind,
                ) = if (kind == this@Memory.kind) ChainFacts(3, first, last) else ChainFacts(0, null, null)

                override fun hashAt(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                    kind: RecordKind,
                    sequence: Long,
                ): String? = null
            }

        private val store =
            object : IntegrityStore {
                override fun lock(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                ) {
                    order += "lock"
                }

                override fun failureExists(
                    tenant: TenantId,
                    row: IntegrityFailureRow,
                ) = rows.any {
                    it.entityRef == row.entityRef && it.period == row.period && it.kind == row.kind &&
                        it.failure == row.failure && it.failingSequence == row.failingSequence
                }

                override fun insertFailure(
                    tenant: TenantId,
                    row: IntegrityFailureRow,
                ) {
                    order += "row"
                    rows += row
                }

                override fun insertRun(
                    tenant: TenantId,
                    row: IntegrityRunRow,
                ) {
                    order += "run"
                    runs += row
                }

                override fun latestRun(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                ) = runs.lastOrNull()
            }

        private val outbox =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> {
                    order += "event"
                    this@Memory.events += events
                    contexts += context
                    return emptyList()
                }
            }

        private val verification =
            ChainVerification(
                { _, _, _, _ ->
                    if (broken) {
                        ChainVerificationResult.Broken(1, ContentHash("a".repeat(64)), ContentHash("b".repeat(64)))
                    } else {
                        ChainVerificationResult.Valid(3)
                    }
                },
                VerificationSources(
                    reads,
                    { _, _, _ -> listOfNotNull(digest) },
                    { _, _ -> copyMatches },
                ),
            )

        private val recorder =
            ChainVerificationRecorder(
                verification,
                reads,
                store,
                outbox,
                Clock.fixed(runAt, ZoneOffset.UTC),
            )

        fun verify(
            runId: String = "run-1",
            asTenant: TenantId = tenant,
        ): RunCause = recorder.verifyPeriod(asTenant, entity, chainPeriod, runId)
    }

    private fun brokenAudit(entity: String) = Memory(entity, auditPeriod, RecordKind.AUDIT_LOG, broken = true)

    private fun expectedPayload(
        entity: String,
        chainPeriod: String,
        evidenceType: String,
    ) = "{\"range\":{\"from\":\"2026-02-01T00:00:00Z\",\"to\":\"2026-02-03T00:00:00Z\"}," +
        "\"failingRecord\":{\"entityRef\":\"$entity\",\"periodRef\":\"$chainPeriod\"," +
        "\"evidenceType\":\"$evidenceType\",\"sequence\":1,\"kind\":\"BROKEN_CHAIN\"}}"

    private fun Memory.types() = events.map { it.type }

    /** A digest row of the entity with no chain tails; its digest is the true one unless [digest] is given. */
    private fun digestEntry(
        entity: String,
        digest: String? = null,
    ): PeriodDigestEntry {
        val tails = DigestChainType.entries.map { ChainTail(it, 0, null) }
        return PeriodDigestEntry(
            entity,
            period,
            1,
            1,
            last,
            last,
            "ev-1",
            tails,
            digest ?: periodDigest(entity, period, 1, tails),
        )
    }

    @Test
    fun `INV-AC-13293 a broken audit chain of the tenant-wide entity writes one platform event`() {
        val m = brokenAudit(tenantWide)
        m.verify() shouldBe RunCause.VERIFICATION
        m.types() shouldBe listOf(platformType)
        platformType shouldBe "com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailed.v1"
        m.events.single().dataJson shouldBe expectedPayload(tenantWide, auditPeriod, "AUDIT_LOG")
        m.contexts.single().entity shouldBe EntityRef.TENANT_WIDE
        m.contexts.single().tenant shouldBe tenant
        m.contexts.single().correlation shouldBe CorrelationId("run-1")
        m.contexts.single().causation shouldBe CausationId("run-1")
        m.types().none { it == "com.dynokore.gst.evidence.EvidenceIntegrityFailed.v1" } shouldBe true
        m.order shouldBe listOf("lock", "row", "event", "run")
    }

    @Test
    fun `INV-AC-13293 a broken audit chain of a real entity keeps the domain event`() {
        val m = brokenAudit("E")
        m.verify()
        m.types() shouldBe listOf("com.dynokore.gst.evidence.EvidenceIntegrityFailed.v1")
        m.events.single().dataJson shouldBe expectedPayload("E", auditPeriod, "AUDIT_LOG")
        m.contexts.single().entity shouldBe EntityRef("E")
        m.types().none { it == "com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailed.v1" } shouldBe true
    }

    @Test
    fun `INV-AC-13293 a second run for the tenant-wide chain adds no second row and no second event`() {
        val m = brokenAudit(tenantWide)
        m.verify("run-1")
        m.verify("run-2")
        m.rows.size shouldBe 1
        m.events.size shouldBe 1
        m.runs.size shouldBe 2
    }

    @Test
    fun `INV-AC-13293 digest mismatch of a real entity keeps the domain event`() {
        val m = Memory("E", period, RecordKind.EVIDENCE_PAYLOAD, digest = digestEntry("E", "sha256:" + "0".repeat(64)))
        m.verify()
        m.rows.map { it.failure }.distinct() shouldBe listOf(FailureKind.DIGEST_MISMATCH)
        m.types().isNotEmpty() shouldBe true
        m.types().distinct() shouldBe listOf(domainType)
    }

    @Test
    fun `INV-AC-13293 retained copy mismatch of a real entity keeps the domain event`() {
        val m = Memory("E", period, RecordKind.EVIDENCE_PAYLOAD, digest = digestEntry("E"), copyMatches = false)
        m.verify()
        m.rows.map { it.failure }.distinct() shouldBe listOf(FailureKind.RETAINED_COPY_MISMATCH)
        m.types().isNotEmpty() shouldBe true
        m.types().distinct() shouldBe listOf(domainType)
    }

    @Test
    fun `INV-AC-13293 an entity one character from the nil UUID gets the domain event`() {
        val entity = "00000000-0000-0000-0000-000000000001"
        val m = brokenAudit(entity)
        m.verify()
        m.types() shouldBe listOf(domainType)
        m.contexts.single().entity shouldBe EntityRef(entity)
    }

    @Test
    fun `INV-AC-13293 the platform entity of all f gets the domain event`() {
        val entity = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        val m = brokenAudit(entity)
        m.verify(asTenant = TenantId.PLATFORM) // EventContext pairs the platform entity with the platform tenant only
        m.types() shouldBe listOf(domainType)
        m.contexts.single().entity shouldBe EntityRef.PLATFORM
    }
}
