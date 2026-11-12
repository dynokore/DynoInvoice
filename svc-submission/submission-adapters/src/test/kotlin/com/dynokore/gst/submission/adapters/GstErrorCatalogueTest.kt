package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.submission.adapters.catalogue.GstErrorCatalogue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The catalogue adapter reads `ErrorCode`; no value is written as a literal here (INV-REQ-940). */
class GstErrorCatalogueTest {
    private val catalogue = GstErrorCatalogue()

    @Test
    fun `INV-AC-20656 SUB-003 gives the owner and the disposition of ErrorCode SUB_003`() {
        val entry = checkNotNull(catalogue.entryOf(ErrorCode.SUB_003.code))
        entry.code shouldBe ErrorCode.SUB_003.code
        entry.owner shouldBe ErrorCode.SUB_003.owner
        entry.disposition shouldBe ErrorCode.SUB_003.disposition?.name
        (entry.disposition == null) shouldBe false
    }

    @Test
    fun `INV-AC-20656 an unknown code and the look-alikes of SUB-003 give null`() {
        val sub003 = ErrorCode.SUB_003.code
        listOf(
            "X-999",
            sub003.lowercase(),
            sub003 + "0",
            "",
            " $sub003",
        ).forEach { catalogue.entryOf(it) shouldBe null }
    }
}
