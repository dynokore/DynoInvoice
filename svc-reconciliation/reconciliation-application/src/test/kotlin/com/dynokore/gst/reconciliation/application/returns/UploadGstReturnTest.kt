package com.dynokore.gst.reconciliation.application.returns

import com.dynokore.gst.reconciliation.domain.returns.RecordedReturn
import com.dynokore.gst.reconciliation.domain.returns.RefusalReason
import com.dynokore.gst.reconciliation.domain.returns.ReturnForm
import com.dynokore.gst.reconciliation.domain.returns.ReturnUpload
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UploadGstReturnTest {
    private val instant = Instant.parse("2026-04-15T01:02:03Z")
    private val filedAt = Instant.parse("2026-04-10T00:00:00Z")
    private val boxes = mapOf("1" to BigDecimal("100.00"))

    private fun stored(
        version: Int,
        ack: String,
        by: String,
        ref: String? = null,
    ) = StoredReturn(
        RecordedReturn(version, ReturnForm.F5, filedAt, ack, null),
        boxes,
        ref,
        "Preparer $by",
        by,
        instant,
    )

    private fun upload(
        version: Int,
        ack: String?,
    ) = ReturnUpload(version, ReturnForm.F5, filedAt, ack, null)

    private class Fixture(
        initial: List<StoredReturn>,
        clock: Clock,
    ) {
        val store = initial.toMutableList()
        val calls = mutableListOf<String>()
        var raceWinner: StoredReturn? = null

        val records =
            object : ReturnRecords {
                override fun recorded(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                ): List<StoredReturn> {
                    calls += "read"
                    return store.toList()
                }

                override fun insertIfAbsent(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                    stored: StoredReturn,
                ): Boolean {
                    calls += "insert"
                    raceWinner?.let {
                        store += it
                        return false
                    }
                    store += stored
                    return true
                }
            }
        val published = mutableListOf<List<Any?>>()
        val triggered = mutableListOf<List<Any?>>()
        val useCase =
            UploadGstReturn(
                records,
                { t, e, p, s ->
                    calls += "publish"
                    val r = s.recorded
                    published += listOf(t, e, p, r.version, s.filingReference, r.filingAcknowledgement)
                },
                { t, e, p, v ->
                    calls += "trigger"
                    triggered += listOf(t, e, p, v)
                },
                clock,
            )
    }

    private val v2 = stored(2, "ACK2", "U0")

    private fun fixture() = Fixture(listOf(v2), Clock.fixed(instant, ZoneOffset.UTC))

    private fun Fixture.run(
        up: ReturnUpload,
        ref: String? = null,
        by: String = "U1",
    ) = useCase.execute("T1", "E1", "P1", up, boxes, ref, "Preparer $by", by)

    @Test
    fun `INV-AC-24364 (a) a new version is created, stored, published once and triggered once`() {
        val f = fixture()
        val result = f.run(upload(3, "ACK3"), "REF3")
        val expected = stored(3, "ACK3", "U1", "REF3")
        result shouldBe UploadResult.Created(expected)
        f.store shouldBe listOf(v2, expected)
        f.published shouldBe listOf(listOf("T1", "E1", "P1", 3, "REF3", "ACK3"))
        f.triggered shouldBe listOf(listOf("T1", "E1", "P1", 3))
        f.calls shouldBe listOf("read", "insert", "publish", "trigger")
    }

    @Test
    fun `INV-AC-24364 (b) a repeat is replayed with the original and no second publish or trigger`() {
        val f = fixture()
        f.run(upload(3, "ACK3"), "REF3")
        val result = f.run(upload(3, "ACK3"), "REF3", "U2")
        result shouldBe UploadResult.Replayed(stored(3, "ACK3", "U1", "REF3"))
        f.store.size shouldBe 2
        f.published.size shouldBe 1
        f.triggered.size shouldBe 1
    }

    @Test
    fun `INV-AC-24364 (c) a lower version is refused and nothing is stored, published or triggered`() {
        val f = fixture()
        f.run(upload(1, "ACK1")) shouldBe UploadResult.Refused(RefusalReason.VERSION_LOWER_THAN_RECORDED)
        f.store shouldBe listOf(v2)
        f.published shouldBe emptyList()
        f.triggered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24364 (d) a null acknowledgement is refused with PER-014`() {
        val f = fixture()
        val result = f.run(upload(4, null))
        result shouldBe UploadResult.Refused(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT)
        (result as UploadResult.Refused).reason.code shouldBe "PER-014"
        f.store shouldBe listOf(v2)
        f.published shouldBe emptyList()
        f.triggered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24364 (e) a blank acknowledgement is refused with PER-014`() {
        val f = fixture()
        f.run(upload(4, "")) shouldBe UploadResult.Refused(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT)
        f.store shouldBe listOf(v2)
        f.published shouldBe emptyList()
        f.triggered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24364 (f) a repeat version with a blank acknowledgement is refused, not replayed`() {
        val f = fixture()
        f.run(upload(2, "")) shouldBe UploadResult.Refused(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT)
        f.store shouldBe listOf(v2)
        f.published shouldBe emptyList()
        f.triggered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24364 (g) a version without filing reference is created and published with a null reference`() {
        val f = fixture()
        f.run(upload(5, "ACK5"), null) shouldBe UploadResult.Created(stored(5, "ACK5", "U1", null))
        f.published shouldBe listOf(listOf("T1", "E1", "P1", 5, null, "ACK5"))
        f.triggered shouldBe listOf(listOf("T1", "E1", "P1", 5))
    }

    @Test
    fun `INV-AC-24364 (h) a lost insert race is replayed with the stored winner, no publish and no trigger`() {
        val f = fixture()
        val winner = stored(6, "ACK6", "U1", "REF6")
        f.raceWinner = winner
        f.run(upload(6, "ACK6"), "REF6", "U2") shouldBe UploadResult.Replayed(winner)
        f.published shouldBe emptyList()
        f.triggered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24364 (i) the stored return carries the acting user ID and a replay carries the original`() {
        val f = fixture()
        val created = f.run(upload(3, "ACK3"), "REF3") as UploadResult.Created
        created.stored.uploadedBy shouldBe "U1"
        (f.run(upload(3, "ACK3"), "REF3", "U2") as UploadResult.Replayed).stored.uploadedBy shouldBe "U1"
        val g = fixture()
        g.raceWinner = stored(6, "ACK6", "U1")
        (g.run(upload(6, "ACK6"), null, "U2") as UploadResult.Replayed).stored.uploadedBy shouldBe "U1"
    }

    @Test
    fun `INV-AC-24364 (j) a blank acting user ID throws before any port call`() {
        val f = fixture()
        shouldThrow<IllegalArgumentException> { f.run(upload(3, "ACK3"), "REF3", "") }
        f.calls shouldBe emptyList()
        f.store shouldBe listOf(v2)
        f.published shouldBe emptyList()
        f.triggered shouldBe emptyList()
    }
}
