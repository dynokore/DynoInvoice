package com.dynokore.gst.submission.domain.adjustment

import com.dynokore.gst.submission.domain.adjustment.CreditNoteIdentityVerdict.Refused
import com.dynokore.gst.submission.domain.adjustment.CreditNoteIdentityVerdict.Separate
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CreditNoteIdentityTest {
    private val a = "0b8f2c1e-5d3a-4e7b-9c10-2f6a8d4e1b37"
    private val b = "7c9d1e2f-3a4b-4c5d-8e6f-0a1b2c3d4e5f"

    private fun bad() = listOf("1-1-1-1-1", "", "   ", "$a ")

    @Test
    fun `INV-AC-14146 two different well formed UUIDs and no registered kind give Separate`() {
        CreditNoteIdentity.check(a, b, null) shouldBe Separate(a, b)
    }

    @Test
    fun `INV-AC-14146 a null preceding Doc UUID gives Separate`() {
        CreditNoteIdentity.check(a, null, null) shouldBe Separate(a, null)
    }

    @Test
    fun `INV-AC-14146 a registered kind CREDIT_NOTE gives Separate`() {
        CreditNoteIdentity.check(a, b, RowKind.CREDIT_NOTE) shouldBe Separate(a, b)
    }

    @Test
    fun `INV-AC-14146 the same UUID twice gives SAME_AS_PRECEDING`() {
        CreditNoteIdentity.check(a, a, null) shouldBe Refused(IdentityRefusal.SAME_AS_PRECEDING)
    }

    @Test
    fun `INV-AC-14146 the same UUID with one copy in upper case gives SAME_AS_PRECEDING`() {
        CreditNoteIdentity.check(a, a.uppercase(), null) shouldBe Refused(IdentityRefusal.SAME_AS_PRECEDING)
        CreditNoteIdentity.check(a.uppercase(), a, null) shouldBe Refused(IdentityRefusal.SAME_AS_PRECEDING)
    }

    @Test
    fun `INV-AC-14146 a registered kind INVOICE gives REGISTERED_AS_INVOICE`() {
        CreditNoteIdentity.check(a, b, RowKind.INVOICE) shouldBe Refused(IdentityRefusal.REGISTERED_AS_INVOICE)
        CreditNoteIdentity.check(a, null, RowKind.INVOICE) shouldBe Refused(IdentityRefusal.REGISTERED_AS_INVOICE)
    }

    @Test
    fun `INV-AC-14146 a malformed credit note Doc UUID gives MALFORMED_DOC_UUID`() {
        bad().forEach { CreditNoteIdentity.check(it, b, null) shouldBe Refused(IdentityRefusal.MALFORMED_DOC_UUID) }
    }

    @Test
    fun `INV-AC-14146 a malformed preceding Doc UUID gives MALFORMED_DOC_UUID`() {
        bad().forEach { CreditNoteIdentity.check(a, it, null) shouldBe Refused(IdentityRefusal.MALFORMED_DOC_UUID) }
    }

    @Test
    fun `INV-AC-14146 malformed outranks REGISTERED_AS_INVOICE`() {
        CreditNoteIdentity.check("1-1-1-1-1", b, RowKind.INVOICE) shouldBe Refused(IdentityRefusal.MALFORMED_DOC_UUID)
        CreditNoteIdentity.check(a, "", RowKind.INVOICE) shouldBe Refused(IdentityRefusal.MALFORMED_DOC_UUID)
    }

    @Test
    fun `INV-AC-14146 malformed outranks SAME_AS_PRECEDING`() {
        CreditNoteIdentity.check("1-1-1-1-1", "1-1-1-1-1", null) shouldBe Refused(IdentityRefusal.MALFORMED_DOC_UUID)
    }

    @Test
    fun `INV-AC-14146 the same UUID outranks REGISTERED_AS_INVOICE`() {
        CreditNoteIdentity.check(a, a, RowKind.INVOICE) shouldBe Refused(IdentityRefusal.SAME_AS_PRECEDING)
    }

    @Test
    fun `INV-AC-14146 check has three parameters and none is a LifecycleState`() {
        val checks = CreditNoteIdentity::class.java.declaredMethods.filter { it.name == "check" && !it.isSynthetic }
        checks.shouldNotBeEmpty()
        checks.forEach { m ->
            m.parameterCount shouldBe 3
            m.parameterTypes.none { it == LifecycleState::class.java } shouldBe true
        }
    }
}
