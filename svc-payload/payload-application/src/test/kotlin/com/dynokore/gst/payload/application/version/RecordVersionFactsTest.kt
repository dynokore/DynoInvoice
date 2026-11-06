package com.dynokore.gst.payload.application.version

import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.gst.payload.domain.version.totalSgdIncludingTax
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Fake of both ports: one in-memory map keyed by document and version; an existing row is never altered. */
class FakeVersionFacts :
    VersionFactsRecorder,
    VersionFactsReader {
    private val store = HashMap<Pair<UUID, Int>, VersionFacts>()
    var recordCalls = 0
        private set

    override fun record(
        docUuid: UUID,
        version: Int,
        facts: VersionFacts,
    ): Boolean {
        recordCalls++
        return store.putIfAbsent(docUuid to version, facts) == null
    }

    override fun find(
        docUuid: UUID,
        version: Int,
    ): VersionFacts? = store[docUuid to version]?.takeIf { it.totalSgd != null }
}

class RecordVersionFactsTest {
    private val doc = UUID.randomUUID()

    @Test
    fun `second record for the same version returns false and keeps the first`() {
        val fake = FakeVersionFacts()
        val useCase = RecordVersionFacts(fake)
        val first = VersionFacts("E1", BigDecimal("1.00"))
        useCase.record(doc, 1, first) shouldBe true
        useCase.record(doc, 1, VersionFacts("E2", BigDecimal("2.00"))) shouldBe false
        fake.find(doc, 1) shouldBe first
        fake.recordCalls shouldBe 2
    }

    @Test
    fun `find of another version is null`() {
        val fake = FakeVersionFacts()
        RecordVersionFacts(fake).record(doc, 1, VersionFacts("E1", BigDecimal("1.00")))
        fake.find(doc, 2) shouldBe null
    }

    @Test
    fun `find of a row with an absent total is null`() {
        val fake = FakeVersionFacts()
        RecordVersionFacts(fake).record(doc, 1, VersionFacts("E1", null)) shouldBe true
        fake.find(doc, 1) shouldBe null
    }

    @Test
    fun `version below 1 is refused with nothing written`() {
        val fake = FakeVersionFacts()
        assertThrows(IllegalArgumentException::class.java) {
            RecordVersionFacts(fake).record(doc, 0, VersionFacts("E1", null))
        }
        fake.recordCalls shouldBe 0
    }

    @Test
    fun `INV-AC-17178`() {
        totalSgdIncludingTax("SGD", "1000.01", null) shouldBe BigDecimal("1000.01")
        totalSgdIncludingTax("USD", "5.00", "1000.00") shouldBe BigDecimal("1000.00")
        totalSgdIncludingTax("USD", "5.00", null) shouldBe null
        totalSgdIncludingTax("sgd", "1000.01", null) shouldBe null
        totalSgdIncludingTax("SGD ", "1000.01", null) shouldBe null
        assertThrows(IllegalArgumentException::class.java) { totalSgdIncludingTax("SGD", "1e3", null) }
        assertThrows(IllegalArgumentException::class.java) { totalSgdIncludingTax("USD", null, "1000.001") }

        val fake = FakeVersionFacts()
        val useCase = RecordVersionFacts(fake)
        val first = VersionFacts("E1", BigDecimal("1000.01"))
        useCase.record(doc, 1, first) shouldBe true
        useCase.record(doc, 1, VersionFacts("E2", BigDecimal("9.00"))) shouldBe false
        fake.find(doc, 1) shouldBe first
        fake.find(doc, 2) shouldBe null
    }
}
