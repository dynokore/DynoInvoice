package com.dynokore.gst.evidence.domain.auditedread

import com.dynokore.gst.evidence.domain.auditedread.AuditedCall.AcknowledgementChain
import com.dynokore.gst.evidence.domain.auditedread.AuditedCall.AuditPackCreate
import com.dynokore.gst.evidence.domain.auditedread.AuditedCall.AuditPackRead
import com.dynokore.gst.evidence.domain.auditedread.AuditedCall.ReverseLookup
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter.CATEGORY_CODE
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter.COUNTERPARTY
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter.DATE_FROM
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter.DATE_TO
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter.DOCUMENT_NUMBER
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter.DOC_UUID
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter.PERIOD_REF
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

class AuditedReadScopeTest {
    private fun scope(call: AuditedCall) = AuditedReadScope.entryOf(call).scope

    private fun lookup(vararg pairs: Pair<ReverseLookupParameter, String>) = scope(ReverseLookup(linkedMapOf(*pairs)))

    private fun ack(id: String) = scope(AcknowledgementChain(id))

    private val pack = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")

    @Test
    fun `INV-AC-758 a reverse lookup gives its action and the scope in any insertion order`() {
        val expected = "lookup:counterparty=Acme%20Pte%20Ltd&dateFrom=2026-01-01&dateTo=2026-03-31"
        val a =
            ReverseLookup(
                linkedMapOf(
                    COUNTERPARTY to "Acme Pte Ltd",
                    DATE_FROM to "2026-01-01",
                    DATE_TO to "2026-03-31",
                ),
            )
        val b =
            ReverseLookup(
                linkedMapOf(
                    DATE_TO to "2026-03-31",
                    COUNTERPARTY to "Acme Pte Ltd",
                    DATE_FROM to "2026-01-01",
                ),
            )
        AuditedReadScope.entryOf(a) shouldBe AuditedReadEntry("reverseLookup", expected)
        AuditedReadScope.entryOf(b) shouldBe AuditedReadEntry("reverseLookup", expected)
    }

    @Test
    fun `INV-AC-758 all seven parameters are listed in the declared order`() {
        val all = ReverseLookupParameter.entries.reversed().associateWith { "v-" + it.parameterName }
        scope(ReverseLookup(all)) shouldBe
            "lookup:docUuid=v-docUuid&documentNumber=v-documentNumber&counterparty=v-counterparty" +
            "&dateFrom=v-dateFrom&dateTo=v-dateTo&periodRef=v-periodRef&categoryCode=v-categoryCode"
        ReverseLookupParameter.entries shouldBe
            listOf(DOC_UUID, DOCUMENT_NUMBER, COUNTERPARTY, DATE_FROM, DATE_TO, PERIOD_REF, CATEGORY_CODE)
    }

    @Test
    fun `INV-AC-758 an acknowledgement chain call gives the encoded acknowledgement scope`() {
        AuditedReadScope.entryOf(AcknowledgementChain("ACK-1/2")) shouldBe
            AuditedReadEntry("getAcknowledgementChain", "acknowledgement:ACK-1%2F2")
    }

    @Test
    fun `an empty parameter map gives exactly lookup colon`() {
        scope(ReverseLookup(emptyMap())) shouldBe "lookup:"
    }

    @Test
    fun `INV-AC-1972 an audit pack create call gives its action and the entity and period scope`() {
        AuditedReadScope.entryOf(AuditPackCreate("E-001", "2026-Q1")) shouldBe
            AuditedReadEntry("createAuditPackExport", "entity:E-001/period:2026-Q1")
    }

    @Test
    fun `INV-AC-1972 an audit pack read call adds the pack in lower case canonical form`() {
        val expected =
            AuditedReadEntry(
                "getAuditPackExport",
                "entity:E-001/period:2026-Q1/pack:3fa85f64-5717-4562-b3fc-2c963f66afa6",
            )
        AuditedReadScope.entryOf(AuditPackRead("E-001", "2026-Q1", pack)) shouldBe expected
        AuditedReadScope.entryOf(
            AuditPackRead("E-001", "2026-Q1", UUID.fromString("3FA85F64-5717-4562-B3FC-2C963F66AFA6")),
        ) shouldBe expected
    }

    @Test
    fun `INV-AC-1972 the entry has exactly the properties action and scope`() {
        AuditedReadEntry::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet() shouldBe setOf("action", "scope")
    }

    @Test
    fun `a reference holding the period separator does not collide with the split reference`() {
        scope(AuditPackCreate("E-001/period:X", "Y")) shouldNotBe scope(AuditPackCreate("E-001", "X/period:Y"))
        scope(AuditPackCreate("E-001/period:X", "Y")) shouldBe "entity:E-001%2Fperiod%3AX/period:Y"
    }

    @Test
    fun `special characters are percent encoded as UTF-8 and the unreserved ones stay`() {
        ack("a&b") shouldBe "acknowledgement:a%26b"
        ack("a=b") shouldBe "acknowledgement:a%3Db"
        ack("a%b") shouldBe "acknowledgement:a%25b"
        ack("a/b") shouldBe "acknowledgement:a%2Fb"
        ack("a:b") shouldBe "acknowledgement:a%3Ab"
        ack("a b") shouldBe "acknowledgement:a%20b"
        ack("a+b") shouldBe "acknowledgement:a%2Bb"
        ack("a,b") shouldBe "acknowledgement:a%2Cb"
        ack("é") shouldBe "acknowledgement:%C3%A9"
        ack("€") shouldBe "acknowledgement:%E2%82%AC"
        ack("a~b-c.d_e") shouldBe "acknowledgement:a~b-c.d_e"
        ack("AZaz09") shouldBe "acknowledgement:AZaz09"
    }

    @Test
    fun `the hexadecimal digits are upper case`() {
        ack("/") shouldBe "acknowledgement:%2F"
        ack("a\nb") shouldBe "acknowledgement:a%0Ab"
        ack("ÿ") shouldBe "acknowledgement:%C3%BF"
    }

    @Test
    fun `a parameter value that already holds a percent escape is encoded again`() {
        lookup(COUNTERPARTY to "a%20b") shouldBe "lookup:counterparty=a%2520b"
    }

    @Test
    fun `a parameter value holding the separators cannot forge a second parameter`() {
        lookup(COUNTERPARTY to "x&dateFrom=2026-01-01") shouldBe "lookup:counterparty=x%26dateFrom%3D2026-01-01"
    }

    @Test
    fun `a blank parameter value is refused`() {
        shouldThrow<IllegalArgumentException> { lookup(COUNTERPARTY to "") }
        shouldThrow<IllegalArgumentException> { lookup(COUNTERPARTY to " ") }
        shouldThrow<IllegalArgumentException> { lookup(DATE_FROM to "2026-01-01", PERIOD_REF to "\t") }
    }

    @Test
    fun `a blank acknowledgement id is refused and a hundred characters are accepted`() {
        shouldThrow<IllegalArgumentException> { ack("") }
        shouldThrow<IllegalArgumentException> { ack(" ") }
        ack("a".repeat(100)) shouldBe "acknowledgement:" + "a".repeat(100)
        shouldThrow<IllegalArgumentException> { ack("a".repeat(101)) }
    }

    @Test
    fun `a blank entity or period reference is refused`() {
        for (blank in listOf("", " ")) {
            shouldThrow<IllegalArgumentException> { scope(AuditPackCreate(blank, "2026-Q1")) }
            shouldThrow<IllegalArgumentException> { scope(AuditPackCreate("E-001", blank)) }
            shouldThrow<IllegalArgumentException> { scope(AuditPackRead(blank, "2026-Q1", pack)) }
            shouldThrow<IllegalArgumentException> { scope(AuditPackRead("E-001", blank, pack)) }
        }
    }

    @Test
    fun `the operation values are the real operation ids and the look-alike is not one`() {
        AuditedOperation.entries.map { it.name } shouldBe
            listOf("GET_ACKNOWLEDGEMENT_CHAIN", "REVERSE_LOOKUP", "CREATE_AUDIT_PACK_EXPORT", "GET_AUDIT_PACK_EXPORT")
        AuditedOperation.entries.map { it.operationId }.toSet() shouldBe
            setOf("getAcknowledgementChain", "reverseLookup", "createAuditPackExport", "getAuditPackExport")
        AuditedOperation.entries.none { it.operationId == "GetAcknowledgementChain" } shouldBe true
        AuditedOperation.entries.none {
            it.operationId in
                setOf("getEvidenceRetrieval", "exportWorkPaper", "deleteAtRetentionEnd")
        } shouldBe
            true
        ReverseLookupParameter.entries.none { it.parameterName in setOf("limit", "cursor") } shouldBe true
    }
}
