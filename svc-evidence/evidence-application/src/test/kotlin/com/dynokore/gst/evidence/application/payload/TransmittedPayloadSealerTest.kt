package com.dynokore.gst.evidence.application.payload

import com.dynokore.gst.evidence.domain.payload.Despatch
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.gst.evidence.domain.payload.SealDecision
import com.dynokore.gst.evidence.domain.payload.SealRefusal
import com.dynokore.gst.evidence.domain.payload.TransmittedPayloadSeal
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

private class FakeHeld(
    val calls: MutableList<String>,
) : HeldPayloadStore {
    val rows = mutableMapOf<Pair<UUID, Int>, HeldPayload>()
    var findNullAfterInsert = false
    private var inserted = false

    override fun lock(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ) {
        calls += "lock"
    }

    override fun insert(
        tenant: TenantId,
        eventId: String,
        held: HeldPayload,
        recordedAt: Instant,
    ): Boolean {
        calls += "insert"
        val key = held.docUuid to held.version
        if (rows.containsKey(key)) return false
        rows[key] = held
        inserted = true
        return true
    }

    override fun find(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): HeldPayload? {
        calls += "find"
        if (findNullAfterInsert && inserted) return null
        return rows[docUuid to version]
    }

    override fun remove(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ) {
        calls += "remove"
        rows.remove(docUuid to version)
    }
}

private class FakeParked : ParkedDespatchStore {
    val all = mutableListOf<ParkedDespatch>()

    override fun park(
        tenant: TenantId,
        parked: ParkedDespatch,
    ): Boolean {
        if (all.any { it.eventId == parked.eventId }) return false
        all += parked
        return true
    }

    override fun parkedFor(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): List<ParkedDespatch> = all.filter { it.despatch.docUuid == docUuid && it.despatch.version == version }
}

private class FakeQuarantine(
    val calls: MutableList<String>,
) : PayloadQuarantineStore {
    val entries = mutableListOf<Pair<HeldPayload, QuarantineOrigin>>()

    override fun quarantine(
        tenant: TenantId,
        causedByEventId: String,
        held: HeldPayload,
        origin: QuarantineOrigin,
        recordedAt: Instant,
    ) {
        calls += "quarantine"
        entries += held to origin
    }
}

private class FakeSignals : SealSignals {
    val refusals = mutableListOf<RefusedDespatch>()
    val quarantined = mutableListOf<Triple<String, Int, QuarantineOrigin>>()

    override fun despatchRefused(
        tenant: TenantId,
        refused: RefusedDespatch,
    ) {
        refusals += refused
    }

    override fun payloadQuarantined(
        tenant: TenantId,
        causedByEventId: String,
        docUuid: UUID,
        version: Int,
        origin: QuarantineOrigin,
    ) {
        quarantined += Triple(causedByEventId, version, origin)
    }
}

private class FakeSink : SealedPayloadSink {
    val records = mutableMapOf<String, ByteArray>()
    var appendCalls = 0

    override fun append(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        eventId: String,
        content: ByteArray,
        arrivedAt: Instant,
    ) {
        appendCalls++
        records.putIfAbsent(eventId, content)
    }
}

class TransmittedPayloadSealerTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val doc = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val at = Instant.parse("2026-10-30T01:02:03Z")
    private val xml = "<Invoice>x</Invoice>".toByteArray(Charsets.UTF_8)
    private val xml2 = "<Invoice>y</Invoice>".toByteArray(Charsets.UTF_8)

    private val calls = mutableListOf<String>()
    private val held = FakeHeld(calls)
    private val parked = FakeParked()
    private val quarantine = FakeQuarantine(calls)
    private val signals = FakeSignals()
    private val sink = FakeSink()
    private val sealer =
        TransmittedPayloadSealer(held, parked, quarantine, sink, signals, Clock.fixed(at, ZoneOffset.UTC))

    private fun sha(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun payload(
        b: ByteArray = xml,
        version: Int = 1,
        digest: String = sha(b),
    ) = HeldPayload(doc, version, b, digest)

    private fun despatch(
        version: Int = 1,
        digest: String = sha(xml),
        attempt: Int = 1,
    ) = Despatch(doc, version, attempt, digest, at)

    private fun contentOf(
        b: ByteArray = xml,
        version: Int = 1,
        attempt: Int = 1,
    ): ByteArray {
        val d = TransmittedPayloadSeal.decide(payload(b, version), despatch(version, sha(b), attempt))
        return d.shouldBeInstanceOf<SealDecision.Seal>().payload.contentJson()
    }

    @Test
    fun `INV-AC-13386 payload first then despatch seals once with the sealed content`() {
        val r = sealer.onPayloadReady(tenant, "p1", payload())
        r.outcome shouldBe PayloadOutcome.STORED
        r.sealedEventIds.shouldBeEmpty()
        sink.appendCalls shouldBe 0

        sealer.onDespatched(tenant, "d1", despatch()) shouldBe DespatchResult(SealOutcome.SEALED, null)
        sink.appendCalls shouldBe 1
        sink.records.getValue("d1").contentEquals(contentOf()) shouldBe true
    }

    @Test
    fun `INV-AC-13386 despatch first is parked then sealed once with its own event id`() {
        sealer.onDespatched(tenant, "d1", despatch()) shouldBe DespatchResult(SealOutcome.PARKED, null)
        sink.appendCalls shouldBe 0

        val r = sealer.onPayloadReady(tenant, "p1", payload())
        r.outcome shouldBe PayloadOutcome.STORED
        r.sealedEventIds shouldContainExactly listOf("d1")
        sink.records.keys shouldBe setOf("d1")
    }

    @Test
    fun `INV-AC-13386 a repeated payload is a duplicate and stores no further record`() {
        sealer.onDespatched(tenant, "d1", despatch())
        sealer.onPayloadReady(tenant, "p1", payload())
        val again = sealer.onPayloadReady(tenant, "p1", payload())
        again shouldBe PayloadReadyResult(PayloadOutcome.DUPLICATE, null, emptyList(), emptyList())
        sink.appendCalls shouldBe 1
        sink.records.size shouldBe 1
    }

    @Test
    fun `INV-AC-13386 a repeated despatch parks nothing a second time`() {
        sealer.onDespatched(tenant, "d1", despatch())
        sealer.onDespatched(tenant, "d1", despatch())
        parked.all.size shouldBe 1
    }

    @Test
    fun `INV-AC-13386 replay of a sealed despatch still holds one record`() {
        sealer.onPayloadReady(tenant, "p1", payload())
        sealer.onDespatched(tenant, "d1", despatch()) shouldBe DespatchResult(SealOutcome.SEALED, null)
        sealer.onDespatched(tenant, "d1", despatch()) shouldBe DespatchResult(SealOutcome.SEALED, null)
        sink.records.size shouldBe 1
        sink.records.getValue("d1").contentEquals(contentOf()) shouldBe true
    }

    @Test
    fun `INV-AC-13386 a despatch with another digest is refused signalled and stays parked`() {
        sealer.onPayloadReady(tenant, "p1", payload())
        val bad = despatch(digest = sha(xml2))
        val r = sealer.onDespatched(tenant, "d1", bad)
        r shouldBe DespatchResult(SealOutcome.REFUSED, SealRefusal.DESPATCHED_DIGEST_MISMATCH)
        sink.records.shouldBeEmptyMap()
        signals.refusals shouldContainExactly listOf(RefusedDespatch("d1", bad, SealRefusal.DESPATCHED_DIGEST_MISMATCH))
        parked.parkedFor(tenant, doc, 1) shouldContainExactly listOf(ParkedDespatch("d1", bad))
    }

    @Test
    fun `INV-AC-13386 a refused parked despatch is returned and signalled while the next one is still sealed`() {
        val bad = despatch(digest = sha(xml2))
        sealer.onDespatched(tenant, "d1", bad) shouldBe DespatchResult(SealOutcome.PARKED, null)
        sealer.onDespatched(tenant, "d2", despatch(attempt = 2)) shouldBe DespatchResult(SealOutcome.PARKED, null)

        val r = sealer.onPayloadReady(tenant, "p1", payload())
        r.outcome shouldBe PayloadOutcome.STORED
        val expected = RefusedDespatch("d1", bad, SealRefusal.DESPATCHED_DIGEST_MISMATCH)
        r.refused shouldContainExactly listOf(expected)
        signals.refusals shouldContainExactly listOf(expected)
        r.sealedEventIds shouldContainExactly listOf("d2")
        sink.records.keys shouldBe setOf("d2")
    }

    @Test
    fun `INV-AC-13386 a payload failing its digest is rejected quarantined and does not block the version`() {
        sealer.onDespatched(tenant, "d1", despatch())
        val corrupt = payload(digest = sha(xml2))
        val r = sealer.onPayloadReady(tenant, "p0", corrupt)
        r.outcome shouldBe PayloadOutcome.REJECTED
        r.rejection shouldBe SealRefusal.HELD_BYTES_DIGEST_MISMATCH
        held.find(tenant, doc, 1) shouldBe null
        quarantine.entries.map { it.second } shouldContainExactly listOf(QuarantineOrigin.RECEIVED)
        signals.quarantined shouldContainExactly listOf(Triple("p0", 1, QuarantineOrigin.RECEIVED))
        parked.parkedFor(tenant, doc, 1).size shouldBe 1
        sink.records.shouldBeEmptyMap()

        val ok = sealer.onPayloadReady(tenant, "p1", payload())
        ok.outcome shouldBe PayloadOutcome.STORED
        ok.sealedEventIds shouldContainExactly listOf("d1")
    }

    @Test
    fun `INV-AC-13386 a second different payload is a conflict and replaces nothing`() {
        sealer.onPayloadReady(tenant, "p1", payload())
        val r = sealer.onPayloadReady(tenant, "p2", payload(xml2))
        r.outcome shouldBe PayloadOutcome.CONFLICT
        sink.records.shouldBeEmptyMap()
        held.find(tenant, doc, 1)!!.payloadSha256 shouldBe sha(xml)
    }

    @Test
    fun `a stored corrupt payload refuses the despatch then is moved aside by a correct payload`() {
        held.rows[doc to 1] = payload(digest = sha(xml2))
        sealer.onDespatched(tenant, "d1", despatch()) shouldBe
            DespatchResult(SealOutcome.REFUSED, SealRefusal.HELD_BYTES_DIGEST_MISMATCH)
        signals.refusals.size shouldBe 1

        calls.clear()
        val r = sealer.onPayloadReady(tenant, "p1", payload())
        r.outcome shouldBe PayloadOutcome.STORED
        quarantine.entries.map { it.second } shouldContainExactly listOf(QuarantineOrigin.STORED)
        signals.quarantined shouldContainExactly listOf(Triple("p1", 1, QuarantineOrigin.STORED))
        r.sealedEventIds shouldContainExactly listOf("d1")
        sink.records.keys shouldBe setOf("d1")
        (calls.indexOf("quarantine") < calls.indexOf("remove")) shouldBe true
    }

    @Test
    fun `version 2 is accepted and sealed while version 1 sits in quarantine`() {
        sealer.onPayloadReady(tenant, "p0", payload(digest = sha(xml2)))
        sealer.onPayloadReady(tenant, "p2", payload(xml2, version = 2)).outcome shouldBe PayloadOutcome.STORED
        sealer.onDespatched(tenant, "d2", despatch(version = 2, digest = sha(xml2))) shouldBe
            DespatchResult(SealOutcome.SEALED, null)
        sink.records.keys shouldBe setOf("d2")
    }

    @Test
    fun `redrive with no held payload is empty and after repair seals the parked despatch`() {
        sealer.onDespatched(tenant, "d1", despatch())
        sealer.redrive(tenant, doc, 1) shouldBe RedriveResult(emptyList(), emptyList())
        sink.records.shouldBeEmptyMap()

        held.rows[doc to 1] = payload()
        sealer.redrive(tenant, doc, 1) shouldBe RedriveResult(listOf("d1"), emptyList())
        sink.records.keys shouldBe setOf("d1")
    }

    @Test
    fun `two despatch events of one version store two records`() {
        sealer.onPayloadReady(tenant, "p1", payload())
        sealer.onDespatched(tenant, "d1", despatch(attempt = 1))
        sealer.onDespatched(tenant, "d2", despatch(attempt = 2))
        sink.records.keys shouldBe setOf("d1", "d2")
    }

    @Test
    fun `a despatch of version 2 does not seal version 1`() {
        sealer.onPayloadReady(tenant, "p1", payload())
        sealer.onDespatched(tenant, "d2", despatch(version = 2)) shouldBe DespatchResult(SealOutcome.PARKED, null)
        sink.records.shouldBeEmptyMap()
    }

    @Test
    fun `lock is called before find and before insert`() {
        sealer.onDespatched(tenant, "d1", despatch())
        calls shouldContainExactly listOf("lock", "find")
        calls.clear()
        sealer.onPayloadReady(tenant, "p1", payload())
        calls.take(2) shouldContainExactly listOf("lock", "insert")
    }

    @Test
    fun `redrive locks before it reads the held payload`() {
        sealer.redrive(tenant, doc, 1)
        calls shouldContainExactly listOf("lock", "find")
    }

    @Test
    fun `a find returning null after a successful insert fails closed`() {
        sealer.onDespatched(tenant, "d1", despatch())
        held.findNullAfterInsert = true
        shouldThrow<IllegalStateException> { sealer.onPayloadReady(tenant, "p1", payload()) }
    }

    private fun Map<*, *>.shouldBeEmptyMap() = (size shouldBe 0)
}
