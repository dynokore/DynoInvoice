package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.CodeCatalogue
import com.dynokore.gst.payload.adapters.persistence.StoredBlockingFindings
import com.dynokore.gst.payload.application.finding.FindingsReader
import com.dynokore.gst.payload.application.override.FindingDisposition
import com.dynokore.gst.payload.domain.finding.StoredFinding
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class StoredBlockingFindingsTest {
    private val doc = UUID.randomUUID()
    private val catalogue = CodeCatalogue { it == "PAY-011" }

    private fun over(vararg stored: StoredFinding) =
        StoredBlockingFindings(
            object : FindingsReader {
                override fun blocking(
                    docUuid: UUID,
                    version: Int,
                ) = if (version == 1) stored.toList() else emptyList()
            },
            catalogue,
        )

    private fun f(
        code: String,
        field: String,
        overridable: Boolean,
    ) = StoredFinding(code, field, "m", overridable)

    @Test
    fun `PAY-011 stored for fields a true and b false is not overridable`() {
        over(f("PAY-011", "a", true), f("PAY-011", "b", false)).disposition(doc, 1, "PAY-011") shouldBe
            FindingDisposition.REFUSED
    }

    @Test
    fun `PAY-011 stored for both fields true is overridable`() {
        over(f("PAY-011", "a", true), f("PAY-011", "b", true)).disposition(doc, 1, "PAY-011") shouldBe
            FindingDisposition.OVERRIDABLE
    }

    @Test
    fun `a code stored for version 1 is not carried by version 2`() {
        over(f("PAY-011", "", true)).disposition(doc, 2, "PAY-011") shouldBe FindingDisposition.REFUSED
    }

    @Test
    fun `a code the version does not carry or that differs by case or length is refused`() {
        val source = over(f("PAY-011", "", true))
        for (code in listOf("PAY-0111", "pay-011", "PAY-011 ", "PAY-012")) {
            source.disposition(doc, 1, code) shouldBe FindingDisposition.REFUSED
        }
    }

    @Test
    fun `a stored overridable finding of a code the catalogue does not hold as overridable is refused`() {
        over(f("PAY-001", "", true)).disposition(doc, 1, "PAY-001") shouldBe FindingDisposition.REFUSED
    }

    @Test
    fun `an unreadable store is refused`() {
        val broken =
            object : FindingsReader {
                override fun blocking(
                    docUuid: UUID,
                    version: Int,
                ): List<StoredFinding> = error("unreadable")
            }
        StoredBlockingFindings(broken, catalogue).disposition(doc, 1, "PAY-011") shouldBe FindingDisposition.REFUSED
    }
}
