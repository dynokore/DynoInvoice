package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPeriodExtendedDataReader
import com.dynokore.gst.submission.application.extension.ExtensionScopeKind
import com.dynokore.gst.submission.application.extension.ExtensionTarget
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The PeriodExtended reader over Jackson (INV-AC-20595). */
class JacksonPeriodExtendedDataReaderTest {
    private val reader = JacksonPeriodExtendedDataReader()

    private fun json(
        target: String = "SUBMISSION_DUE_DATE",
        scope: String = """{"kind":"PERIOD"}""",
        extra: String = """"newSubmissionDueDate":"2026-05-14",""",
        period: String = "\"period\":\"2026-Q1\",",
        iras: String = "\"irasReference\":\"IRAS-1\",",
    ) = """{"entityRef":"E",$period"target":"$target",$extra$iras"scope":$scope}"""

    private fun refused(text: String) = shouldThrow<ContractViolation> { reader.read(text) }

    @Test
    fun `INV-AC-20595 a valid payload of each scope kind is read`() {
        val p = reader.read(json())
        p.entityRef shouldBe "E"
        p.period shouldBe "2026-Q1"
        p.target shouldBe ExtensionTarget.SUBMISSION_DUE_DATE
        p.newSubmissionDueDate shouldBe LocalDate.of(2026, 5, 14)
        p.scope.kind shouldBe ExtensionScopeKind.PERIOD
        val d = reader.read(json(scope = """{"kind":"DOCUMENT_SET","documentRefs":["a","b"]}""")).scope
        d.kind shouldBe ExtensionScopeKind.DOCUMENT_SET
        d.documentRefs shouldBe listOf("a", "b")
        val range = """{"kind":"DATE_RANGE","dateFrom":"2026-04-01","dateTo":"2026-04-03"}"""
        val r = reader.read(json(scope = range)).scope
        r.dateFrom shouldBe LocalDate.of(2026, 4, 1)
        r.dateTo shouldBe LocalDate.of(2026, 4, 3)
        val bothDates = """"newFilingDueDate":"2026-05-07","newSubmissionDueDate":"2026-05-14","""
        val both = reader.read(json("BOTH", extra = bothDates))
        both.newFilingDueDate shouldBe LocalDate.of(2026, 5, 7)
        val filingOnly = reader.read(json("FILING_DUE_DATE", extra = """"newFilingDueDate":"2026-05-07","""))
        filingOnly.newSubmissionDueDate shouldBe null
    }

    @Test
    fun `INV-AC-20595 a missing required member is refused`() {
        refused(json(iras = ""))
        refused(json(period = ""))
        refused("""{"entityRef":"E","period":"P","target":"SUBMISSION_DUE_DATE","irasReference":"I"}""")
        refused(json(iras = "\"irasReference\":\"\","))
        refused("[]")
        refused("not json")
    }

    @Test
    fun `INV-AC-20595 a target without its new date is refused`() {
        refused(json(extra = ""))
        refused(json("FILING_DUE_DATE", extra = """"newSubmissionDueDate":"2026-05-14","""))
        refused(json("BOTH", extra = """"newSubmissionDueDate":"2026-05-14","""))
        refused(json("BOTH", extra = """"newFilingDueDate":"2026-05-07","""))
    }

    @Test
    fun `INV-AC-20595 a non-date value is refused without echoing it`() {
        refused(json(extra = """"newSubmissionDueDate":"CANARY-VALUE",""")).message!! shouldNotContain "CANARY"
        refused(json(extra = """"newSubmissionDueDate":20260514,"""))
        refused(json(extra = """"newSubmissionDueDate":"2026-02-30","""))
    }

    @Test
    fun `INV-AC-20595 a scope that is not well formed is refused`() {
        refused(json(scope = """{"kind":"DOCUMENT_SET"}"""))
        refused(json(scope = """{"kind":"DOCUMENT_SET","documentRefs":[]}"""))
        refused(json(scope = """{"kind":"DOCUMENT_SET","documentRefs":[1]}"""))
        refused(json(scope = """{"kind":"DATE_RANGE","dateFrom":"2026-04-01"}"""))
        refused(json(scope = """{"kind":"DATE_RANGE","dateTo":"2026-04-01"}"""))
        refused(json(scope = """{"kind":"DATE_RANGE","dateFrom":"2026-04-01","dateTo":"x"}"""))
        refused(json(scope = """{}"""))
        refused(json(scope = "null"))
    }

    @Test
    fun `INV-AC-20595 the look-alike scope kind DATE-RANGE and a lower-case kind are refused`() {
        refused(json(scope = """{"kind":"DATE-RANGE","dateFrom":"2026-04-01","dateTo":"2026-04-03"}"""))
        refused(json(scope = """{"kind":"period"}"""))
        refused(json(target = "submission_due_date"))
        refused(json(target = "OTHER"))
    }
}
