package com.dynokore.gst.payload.application.finding

import com.dynokore.gst.payload.domain.finding.StoredFinding
import com.dynokore.gst.payload.domain.finding.ValidationReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/** Fake of both ports: one in-memory map keyed by document and version, counting `replace` calls. */
class FakeFindings :
    FindingsRecorder,
    FindingsReader {
    private val store = HashMap<Pair<UUID, Int>, List<StoredFinding>>()
    var replaceCalls = 0
        private set

    override fun replace(
        docUuid: UUID,
        version: Int,
        findings: List<StoredFinding>,
    ) {
        replaceCalls++
        store[docUuid to version] = findings
    }

    override fun blocking(
        docUuid: UUID,
        version: Int,
    ): List<StoredFinding> = store[docUuid to version].orEmpty()
}

class RecordValidationFindingsTest {
    private val doc = UUID.randomUUID()

    private fun r(
        code: String,
        severity: String?,
        overridable: Boolean?,
        field: String? = "x",
    ) = ValidationReason(code, field, "m-$code", severity, overridable)

    private val valid =
        listOf(
            r("PAY-011", "BLOCK", true),
            r("PAY-001", "BLOCK", false),
            r("PAY-004", "WARN", null),
            r("PAY-009", "INFO", null),
            r("PAY-011", "BLOCK", false),
            r("PAY-020", "BLOCK", null),
        )

    @Test
    fun `INV-AC-17111`() {
        val fake = FakeFindings()
        val useCase = RecordValidationFindings(fake)

        useCase.record(doc, 1, valid)
        fake.replaceCalls shouldBe 1
        fake.blocking(doc, 1).map { Triple(it.code, it.field, it.overridable) }.toSet() shouldBe
            setOf(Triple("PAY-011", "x", false), Triple("PAY-001", "x", false), Triple("PAY-020", "x", false))
        fake.blocking(doc, 1).first { it.code == "PAY-011" }.message shouldBe "m-PAY-011"
        fake.blocking(doc, 1).none { it.code == "PAY-004" || it.code == "PAY-009" } shouldBe true

        val other = FakeFindings()
        val second = RecordValidationFindings(other)
        for (bad in listOf("block", "BLOCK ", "BLOCKED", "FATAL", null)) {
            assertThrows(IllegalArgumentException::class.java) {
                second.record(doc, 1, valid + r("PAY-099", bad, true))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { second.record(doc, 0, valid) }
        other.replaceCalls shouldBe 0
    }

    @Test
    fun `version below 1 is refused and nothing is written`() {
        val fake = FakeFindings()
        assertThrows(IllegalArgumentException::class.java) { RecordValidationFindings(fake).record(doc, -1, valid) }
        fake.replaceCalls shouldBe 0
    }

    @Test
    fun `a second recording replaces the first`() {
        val fake = FakeFindings()
        val useCase = RecordValidationFindings(fake)
        useCase.record(doc, 1, valid)
        useCase.record(doc, 1, emptyList())
        fake.blocking(doc, 1) shouldBe emptyList()
    }
}
