package com.dynokore.gst.evidence.application.integrity

import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.domain.chain.AuditChainKey
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.gst.evidence.domain.digest.periodDigest
import com.dynokore.gst.evidence.domain.integrity.FailureKind
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.gst.evidence.domain.integrity.kind
import com.dynokore.platform.evidencestore.ChainVerificationResult
import com.dynokore.platform.evidencestore.ContentHash
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ChainVerificationTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val entity = "E"
    private val period = "2026-Q1"
    private val digestAt = Instant.parse("2026-04-01T00:00:00Z")
    private val allKinds = listOf(RecordKind.EVIDENCE_PAYLOAD, RecordKind.ACKNOWLEDGEMENT, RecordKind.DECISION_TRACE)

    private fun hash(n: Int) = n.toString(16).padStart(64, '0')

    private fun tailHash(
        kind: RecordKind,
        sequence: Int,
    ) = hash(kind.ordinal * 100 + sequence + 1)

    private class Rec(
        val hash: String,
        val at: Instant,
    )

    private class Key(
        val entity: String,
        val period: String,
        val kind: RecordKind,
    ) {
        override fun equals(other: Any?) =
            other is Key && entity == other.entity && period == other.period && kind == other.kind

        override fun hashCode() = listOf(entity, period, kind).hashCode()
    }

    /** In-memory ports: the chains by (entity, period, kind), the broken sequences, the digests and the copy answer. */
    private inner class Memory {
        val chains = mutableMapOf<Key, List<Rec>>()
        val broken = mutableMapOf<Key, Long>()
        val digests = mutableListOf<PeriodDigestEntry>()
        var copyMatches = true
        var unreadable: RecordKind? = null
        var unreadableCopy = false

        fun chain(
            entityRef: String,
            period: String,
            kind: RecordKind,
            length: Int,
        ) {
            chains[Key(entityRef, period, kind)] =
                (0 until length).map { Rec(tailHash(kind, it), Instant.parse("2026-02-0${it + 1}T00:00:00Z")) }
        }

        fun digest(
            digest: String? = null,
            tails: List<ChainTail>? = null,
        ) {
            val actual =
                tails ?: DigestChainType.entries.map { type ->
                    val recs = chains[Key(entity, period, type.kind)].orEmpty()
                    ChainTail(type, recs.size.toLong(), recs.lastOrNull()?.hash)
                }
            digests +=
                PeriodDigestEntry(
                    entity,
                    period,
                    1,
                    1,
                    digestAt,
                    digestAt,
                    "ev-1",
                    actual,
                    digest ?: periodDigest(entity, period, 1, actual),
                )
        }

        private val checks =
            ChainChecks { _, entityRef, p, kind ->
                check(unreadable != kind) { "unreadable" }
                val at = broken[Key(entityRef, p, kind)]
                if (at == null) {
                    ChainVerificationResult.Valid(chains[Key(entityRef, p, kind)].orEmpty().size)
                } else {
                    ChainVerificationResult.Broken(at, ContentHash(hash(900)), ContentHash(hash(901)))
                }
            }

        private val reads =
            object : ChainReads {
                override fun periods(
                    tenant: TenantId,
                    entityRef: String,
                ) = chains.keys
                    .filter { it.entity == entityRef }
                    .map { it.period }
                    .distinct()

                override fun kinds(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                ) = chains.keys
                    .filter { it.entity == entityRef && it.period == period }
                    .map { it.kind }
                    .sorted()

                override fun facts(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                    kind: RecordKind,
                ): ChainFacts {
                    val recs = chains[Key(entityRef, period, kind)].orEmpty()
                    return ChainFacts(recs.size.toLong(), recs.firstOrNull()?.at, recs.lastOrNull()?.at)
                }

                override fun hashAt(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                    kind: RecordKind,
                    sequence: Long,
                ) = chains[Key(entityRef, period, kind)]?.getOrNull(sequence.toInt())?.hash
            }

        private val sources =
            VerificationSources(
                reads,
                DigestReads { _, entityRef, p -> digests.filter { it.entityRef == entityRef && it.period == p } },
                DigestCopies { _, _ ->
                    check(!unreadableCopy) { "copy unreadable" }
                    copyMatches
                },
            )

        fun verify(
            entityRef: String = entity,
            p: String = period,
        ) = ChainVerification(checks, sources).verifyPeriod(tenant, entityRef, p)
    }

    private fun intact(): Memory =
        Memory().apply {
            chain(entity, period, RecordKind.EVIDENCE_PAYLOAD, 3)
            chain(entity, period, RecordKind.ACKNOWLEDGEMENT, 3)
            chain(entity, period, RecordKind.DECISION_TRACE, 2)
            digest()
        }

    @Test
    fun `intact chains of lengths 3, 3 and 2 and a matching digest give VERIFIED`() {
        val m = intact()
        val outcome = m.verify()
        outcome.result shouldBe RunResult.VERIFIED
        outcome.cause shouldBe RunCause.VERIFICATION
        outcome.chainLength shouldBe 8
        outcome.periodDigest shouldBe m.digests.single().digest
        outcome.failures shouldBe emptyList()
    }

    @Test
    fun `a broken record at sequence 1 of the acknowledgement chain gives one BROKEN_CHAIN with the chain range`() {
        val m = intact()
        m.broken[Key(entity, period, RecordKind.ACKNOWLEDGEMENT)] = 1
        val outcome = m.verify()
        outcome.result shouldBe RunResult.FAILED
        outcome.failures shouldBe
            listOf(
                ChainFailure(
                    RecordKind.ACKNOWLEDGEMENT,
                    FailureKind.BROKEN_CHAIN,
                    1,
                    Instant.parse("2026-02-01T00:00:00Z"),
                    Instant.parse("2026-02-03T00:00:00Z"),
                ),
            )
    }

    @Test
    fun `a recorded tail hash different from the hash at sequence length minus 1 gives DIGEST_MISMATCH`() {
        val m = intact()
        m.digests.clear()
        m.digest(
            tails =
                listOf(
                    ChainTail(DigestChainType.PAYLOAD, 3, hash(777)),
                    ChainTail(DigestChainType.ACKNOWLEDGEMENT, 3, tailHash(RecordKind.ACKNOWLEDGEMENT, 2)),
                    ChainTail(DigestChainType.DECISION_TRACE, 2, tailHash(RecordKind.DECISION_TRACE, 1)),
                ),
        )
        val outcome = m.verify()
        outcome.result shouldBe RunResult.FAILED
        outcome.failures.map { it.kind to it.failure } shouldBe
            listOf(RecordKind.EVIDENCE_PAYLOAD to FailureKind.DIGEST_MISMATCH)
        outcome.failures.single().failingSequence shouldBe null
    }

    @Test
    fun `a chain shorter than the recorded length gives DIGEST_MISMATCH`() {
        val m = intact()
        val key = Key(entity, period, RecordKind.DECISION_TRACE)
        m.chains[key] = m.chains.getValue(key).take(1)
        val outcome = m.verify()
        outcome.result shouldBe RunResult.FAILED
        outcome.failures.map { it.kind to it.failure } shouldBe
            listOf(RecordKind.DECISION_TRACE to FailureKind.DIGEST_MISMATCH)
    }

    @Test
    fun `a different recomputed digest gives three DIGEST_MISMATCH failures, one per chain type in order`() {
        val m = intact()
        m.digests.clear()
        m.digest(digest = "sha256:" + "0".repeat(64))
        val outcome = m.verify()
        outcome.result shouldBe RunResult.FAILED
        outcome.failures.map { it.kind } shouldBe allKinds
        outcome.failures.map { it.failure } shouldBe List(3) { FailureKind.DIGEST_MISMATCH }
        outcome.failures.map { it.failingSequence } shouldBe List(3) { null }
    }

    @Test
    fun `a copy that differs gives three RETAINED_COPY_MISMATCH failures`() {
        val m = intact()
        m.copyMatches = false
        val outcome = m.verify()
        outcome.result shouldBe RunResult.FAILED
        outcome.failures.map { it.kind } shouldBe allKinds
        outcome.failures.map { it.failure } shouldBe List(3) { FailureKind.RETAINED_COPY_MISMATCH }
        outcome.failures.map { it.failingSequence } shouldBe List(3) { null }
    }

    @Test
    fun `with an empty decision trace chain its failure range is the digest recordedAt at both ends`() {
        val m = intact()
        m.chains.remove(Key(entity, period, RecordKind.DECISION_TRACE))
        m.digests.clear()
        m.digest()
        m.copyMatches = false
        val trace = m.verify().failures.single { it.kind == RecordKind.DECISION_TRACE }
        trace.rangeFrom shouldBe digestAt
        trace.rangeTo shouldBe digestAt
    }

    @Test
    fun `a port that throws on reading gives FAILED with cause UNREADABLE, length 0 and no failure`() {
        val m = intact()
        m.unreadable = RecordKind.ACKNOWLEDGEMENT
        val outcome = m.verify()
        outcome.result shouldBe RunResult.FAILED
        outcome.cause shouldBe RunCause.UNREADABLE
        outcome.chainLength shouldBe 0
        outcome.failures shouldBe emptyList()
    }

    @Test
    fun `an object copy that cannot be read gives FAILED with cause UNREADABLE`() {
        val m = intact()
        m.unreadableCopy = true
        val outcome = m.verify()
        outcome.result shouldBe RunResult.FAILED
        outcome.cause shouldBe RunCause.UNREADABLE
        outcome.failures shouldBe emptyList()
    }

    @Test
    fun `the audit period AUDIT-2026-10 is verified through the audit prefix`() {
        val m = Memory()
        val auditPeriod = AuditChainKey.PREFIX + "2026-10"
        auditPeriod shouldBe "AUDIT-2026-10"
        m.chain(entity, auditPeriod, RecordKind.AUDIT_LOG, 3)
        val outcome = m.verify(p = auditPeriod)
        outcome.result shouldBe RunResult.VERIFIED
        outcome.chainLength shouldBe 3
        outcome.periodDigest shouldBe null
    }

    @Test
    fun `a chain of another entity with the same period is not counted in the length`() {
        val m = intact()
        m.chain("OTHER", period, RecordKind.EVIDENCE_PAYLOAD, 3)
        m.verify().chainLength shouldBe 8
    }

    @Test
    fun `an entity whose chain is intact beside a broken one keeps VERIFIED`() {
        val m = intact()
        m.chain("OTHER", period, RecordKind.ACKNOWLEDGEMENT, 2)
        m.broken[Key("OTHER", period, RecordKind.ACKNOWLEDGEMENT)] = 1
        m.verify("OTHER").result shouldBe RunResult.FAILED
        m.verify().result shouldBe RunResult.VERIFIED
    }
}
