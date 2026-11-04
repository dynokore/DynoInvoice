package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.derivation.HeldDocumentReleaseDecision.Refused
import com.dynokore.gst.canonical.domain.derivation.HeldDocumentReleaseDecision.Released
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HeldDocumentReleaseTest {
    private val user = "u-1"

    @Test
    fun `INV-AC-16539 every combination of permitted, hold code and causing user gives the expected decision`() {
        var cases = 0
        val causes = listOf(null, user, "u-2", "U-1")
        for (permitted in listOf(true, false)) {
            for (code in listOf("ING-017", "ING-024", "ING-016", "ING-0240", "ing-024")) {
                for (causedBy in causes) {
                    val expected: HeldDocumentReleaseDecision =
                        when {
                            !permitted -> Refused(ReleaseRefusal.NOT_PERMITTED)
                            code != "ING-017" && code != "ING-024" -> Refused(ReleaseRefusal.NOT_MANUAL_HOLD)
                            causedBy == user -> Refused(ReleaseRefusal.SAME_USER)
                            else -> Released
                        }
                    HeldDocumentRelease.decide(code, permitted, user, causedBy) shouldBe expected
                    cases++
                }
            }
        }
        cases shouldBe 40
    }

    @Test
    fun `INV-AC-16539 the manual hold codes are exactly ING-017 and ING-024`() {
        HeldDocumentRelease.MANUAL_HOLD_CODES shouldBe setOf("ING-017", "ING-024")
    }

    @Test
    fun `INV-AC-16539 a blank releasing user is rejected`() {
        for (blank in listOf("", " ", "\t")) {
            assertThrows<IllegalArgumentException> { HeldDocumentRelease.decide("ING-017", true, blank, null) }
        }
    }

    @Test
    fun `INV-AC-16539 a not permitted caller who is also the causing user gets NOT_PERMITTED`() {
        HeldDocumentRelease.decide("ING-024", false, user, user) shouldBe Refused(ReleaseRefusal.NOT_PERMITTED)
    }

    @Test
    fun `INV-AC-16539 a not permitted caller with a non manual code gets NOT_PERMITTED first`() {
        HeldDocumentRelease.decide("ING-016", false, user, null) shouldBe Refused(ReleaseRefusal.NOT_PERMITTED)
    }

    @Test
    fun `INV-AC-16539 a null causing user does not release a not permitted caller`() {
        HeldDocumentRelease.decide("ING-017", false, user, null) shouldBe Refused(ReleaseRefusal.NOT_PERMITTED)
    }

    @Test
    fun `INV-AC-16539 a look-alike code and a lower case code are not manual hold codes`() {
        HeldDocumentRelease.decide("ING-0240", true, user, null) shouldBe Refused(ReleaseRefusal.NOT_MANUAL_HOLD)
        HeldDocumentRelease.decide("ing-024", true, user, null) shouldBe Refused(ReleaseRefusal.NOT_MANUAL_HOLD)
    }

    @Test
    fun `INV-AC-16539 the causing user comparison is case sensitive`() {
        HeldDocumentRelease.decide("ING-017", true, user, "U-1") shouldBe Released
    }
}
