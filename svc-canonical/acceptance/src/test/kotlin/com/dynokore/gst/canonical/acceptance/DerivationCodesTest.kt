package com.dynokore.gst.canonical.acceptance

import com.dynokore.gst.canonical.domain.derivation.UNAVAILABLE_CODE
import com.dynokore.gst.catalogue.Disposition
import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.catalogue.Severity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** UNAVAILABLE_CODE equals a HOLD, AUTO_RELEASE code of the generated error catalogue, not a copy (INV-T-7131). */
class DerivationCodesTest {
    @Test
    fun `INV-AC-2155 UNAVAILABLE_CODE is a HOLD code of the error catalogue with disposition AUTO_RELEASE`() {
        val entry = ErrorCode.entries.single { it.code == UNAVAILABLE_CODE }
        entry.severity shouldBe Severity.HOLD
        entry.disposition shouldBe Disposition.AUTO_RELEASE
    }
}
