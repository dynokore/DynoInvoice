package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** [ScheduleProblems] against the CPL-010 and CPL-011 rows of the pinned gst-catalogue (INV-T-19764). */
class ScheduleProblemsCatalogueTest {
    private fun check(
        code: String,
        title: String,
        row: ErrorCode,
    ) {
        row.code shouldBe code
        row.condition shouldStartWith title
        row.severity.name shouldBe ScheduleProblems.SEVERITY
        row.disposition?.name shouldBe ScheduleProblems.DISPOSITION
        row.owner shouldBe ScheduleProblems.OWNER
    }

    @Test
    fun `INV-AC-19762 CPL-010 and CPL-011 equal the catalogue rows`() {
        check(ScheduleProblems.CONFLICT_CODE, ScheduleProblems.CONFLICT_TITLE, ErrorCode.CPL_010)
        check(ScheduleProblems.RULE_BROKEN_CODE, ScheduleProblems.RULE_BROKEN_TITLE, ErrorCode.CPL_011)
    }

    @Test
    fun `INV-AC-19762 a 409 carries CPL-010 and a 422 carries CPL-011 with the catalogue title`() {
        val conflict = ScheduleProblems.conflict("SCHEDULE_EXISTS", "c1")
        conflict.status shouldBe 409
        conflict.body shouldContain "\"code\":\"CPL-010\""
        conflict.body shouldContain "\"title\":\"Tenant schedule conflict\""
        val broken = ScheduleProblems.ruleBroken("x", "c2")
        broken.status shouldBe 422
        broken.body shouldContain "\"code\":\"CPL-011\""
        broken.body shouldContain "\"title\":\"Tenant schedule not valid\""
        conflict.body.contains("CPL-011") shouldBe false
        broken.body.contains("CPL-010") shouldBe false
    }
}
