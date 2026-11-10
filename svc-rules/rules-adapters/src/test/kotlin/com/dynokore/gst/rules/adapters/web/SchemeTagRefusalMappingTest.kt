package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.application.MappingPackResult
import com.dynokore.gst.rules.application.RefusalReason
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** A refused wrong-side scheme tag is answered 422 RUL-013 and names the tag (INV-REQ-2444). */
class SchemeTagRefusalMappingTest {
    private fun refusal(result: MappingPackResult.Refused) = checkNotNull(MappingPackMapping.refusalOf(result))

    @Test
    fun `INV-AC-19536 SCHEME_TAG_SIDE with the tag SWS answers 422 RUL-013 with the catalogue severity and disposition`() {
        val r = refusal(MappingPackResult.Refused(RefusalReason.SCHEME_TAG_SIDE, names = listOf("SWS")))
        r.status shouldBe 422
        r.code shouldBe "RUL-013"
        r.severity shouldBe ErrorCode.RUL_013.severity.name
        r.disposition shouldBe ErrorCode.RUL_013.disposition?.name
        r.detail!! shouldContain "SWS"
    }

    @Test
    fun `INV-AC-19536 the tag SWSX is named as given`() {
        val r = refusal(MappingPackResult.Refused(RefusalReason.SCHEME_TAG_SIDE, names = listOf("SWSX")))
        r.detail!! shouldContain "SWSX"
    }

    @Test
    fun `INV-AC-19536 INPUT_INVALID is still 400 CONTRACT_VIOLATION`() {
        val r = refusal(MappingPackResult.Refused(RefusalReason.INPUT_INVALID))
        r.status shouldBe 400
        r.code shouldBe com.dynokore.platform.serviceruntime.http.ClosedErrorCode.CONTRACT_VIOLATION.code
    }

    @Test
    fun `INV-AC-19536 CODE_CHECK with RUL_010 is still 422 RUL-010`() {
        val r = refusal(MappingPackResult.Refused(RefusalReason.CODE_CHECK, code = ErrorCode.RUL_010, names = listOf("e1")))
        r.status shouldBe 422
        r.code shouldBe "RUL-010"
    }
}
