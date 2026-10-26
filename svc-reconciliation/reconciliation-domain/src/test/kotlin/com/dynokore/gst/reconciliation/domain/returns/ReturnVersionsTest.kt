package com.dynokore.gst.reconciliation.domain.returns

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class ReturnVersionsTest {
    private val at = Instant.parse("2026-04-28T09:00:00Z")

    private fun rec(
        version: Int,
        form: ReturnForm = ReturnForm.F5,
        amends: Int? = null,
    ) = RecordedReturn(version, form, at, "ACK-$version", amends)

    private fun up(
        version: Int,
        form: ReturnForm = ReturnForm.F5,
        ack: String? = "ACK",
        amends: Int? = null,
        filedAt: Instant = at,
    ) = ReturnUpload(version, form, filedAt, ack, amends)

    @Test
    fun `INV-AC-1715 a second F5 is accepted and current is v2 with v1 unchanged`() {
        val v1 = rec(1)
        ReturnVersions.decide(listOf(v1), up(2)) shouldBe UploadDecision.Accepted
        val v2 = rec(2)
        ReturnVersions.current(listOf(v1, v2)) shouldBe v2
        v1 shouldBe rec(1)
    }

    @Test
    fun `INV-AC-1715 an upload without the filing acknowledgement is refused with PER-014`() {
        for (ack in listOf(null, "", " ")) {
            val decision = ReturnVersions.decide(listOf(rec(1), rec(2)), up(3, ack = ack))
            decision shouldBe UploadDecision.Refused(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT)
            RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT.code shouldBe "PER-014"
        }
    }

    @Test
    fun `INV-AC-1715 a missing acknowledgement on a recorded version is refused, not repeated`() {
        ReturnVersions.decide(listOf(rec(1)), up(1, ack = null)) shouldBe
            UploadDecision.Refused(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT)
    }

    @Test
    fun `INV-AC-1715 a missing acknowledgement is refused before the order rule`() {
        ReturnVersions.decide(listOf(rec(3)), up(1, ack = " ")) shouldBe
            UploadDecision.Refused(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT)
    }

    @Test
    fun `INV-AC-668 an F7 amending v1 is accepted, current is v2 and v1 is superseded`() {
        ReturnVersions.decide(listOf(rec(1)), up(2, ReturnForm.F7, amends = 1)) shouldBe UploadDecision.Accepted
        val recorded = listOf(rec(1), rec(2, ReturnForm.F7, amends = 1))
        ReturnVersions.current(recorded) shouldBe recorded[1]
        ReturnVersions.superseded(recorded) shouldBe setOf(1)
    }

    @Test
    fun `INV-AC-668 an F7 amending an unrecorded version supersedes nothing`() {
        ReturnVersions.superseded(listOf(rec(1), rec(2, ReturnForm.F7, amends = 9))) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-668 an F5 naming amends does not supersede`() {
        ReturnVersions.superseded(listOf(rec(1), rec(2, ReturnForm.F5, amends = 1))) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-668 an F7 amending a recorded F7 supersedes nothing`() {
        ReturnVersions.superseded(listOf(rec(1, ReturnForm.F7), rec(2, ReturnForm.F7, amends = 1))) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-1715 the same version again is a repeat of the original, whatever its other fields`() {
        val v1 = rec(1)
        val changed = up(1, ReturnForm.F8, ack = "OTHER", amends = 5, filedAt = at.plusSeconds(60))
        ReturnVersions.decide(listOf(v1, rec(2)), changed) shouldBe UploadDecision.Repeat(v1)
    }

    @Test
    fun `INV-AC-1715 a version lower than the highest recorded is refused`() {
        ReturnVersions.decide(listOf(rec(1), rec(3)), up(2)) shouldBe
            UploadDecision.Refused(RefusalReason.VERSION_LOWER_THAN_RECORDED)
        RefusalReason.VERSION_LOWER_THAN_RECORDED.code shouldBe null
    }

    @Test
    fun `INV-AC-1715 versions need not be consecutive`() {
        ReturnVersions.decide(emptyList(), up(5)) shouldBe UploadDecision.Accepted
        ReturnVersions.decide(listOf(rec(1)), up(7)) shouldBe UploadDecision.Accepted
    }

    @Test
    fun `INV-AC-668 current is null for none and ignores list order`() {
        ReturnVersions.current(emptyList()) shouldBe null
        ReturnVersions.current(listOf(rec(3), rec(1), rec(2))) shouldBe rec(3)
        ReturnVersions.current(listOf(rec(1), rec(2), rec(3))) shouldBe rec(3)
    }

    @Test
    fun `the values of ReturnForm are exactly F5 F7 F8 and a value with a space is none of them`() {
        ReturnForm.entries.map { it.value } shouldBe listOf("F5", "F7", "F8")
        ReturnForm.entries.any { it.value == "F5 " } shouldBe false
        ReturnForm.entries.any { it.value == "f5" } shouldBe false
    }

    @Test
    fun `RefusalReason has exactly the two members`() {
        RefusalReason.entries.map { it.name } shouldBe
            listOf("MISSING_FILING_ACKNOWLEDGEMENT", "VERSION_LOWER_THAN_RECORDED")
    }
}
