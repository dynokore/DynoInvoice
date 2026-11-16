package com.dynokore.gst.payload.adapters.build

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MdePackAggregateLiteralsTest {
    private fun entry(
        type: String,
        term: String = "IBT-022",
    ) = """{"aggregateType":"$type","businessTermId":"$term","literal":"$type"}"""

    private fun pack(vararg entries: String) = """{"aggregateDefaults":[${entries.joinToString(",")}]}"""

    @Test
    fun `INV-AC-23492 literal of each aggregate type is read`() {
        val literals = MdePackAggregateLiterals(pack(entry("POS"), entry("STI"), entry("PCP")))
        listOf("POS", "STI", "PCP").forEach {
            literals.literalFor(it) shouldBe AggregateLiteral("IBT-022", it)
        }
    }

    @Test
    fun `INV-AC-23492 absent entry gives null`() {
        MdePackAggregateLiterals(pack(entry("POS"))).literalFor("STI") shouldBe null
        MdePackAggregateLiterals("{}").literalFor("POS") shouldBe null
        MdePackAggregateLiterals(pack(entry("POS"))).literalFor("pos") shouldBe null
    }

    @Test
    fun `INV-AC-23492 duplicate entry fails closed`() {
        shouldThrow<AggregateLiteralParseException> {
            MdePackAggregateLiterals(pack(entry("POS"), entry("POS"))).literalFor("POS")
        }
        shouldThrow<AggregateLiteralParseException> {
            MdePackAggregateLiterals(pack(entry("POS"), entry("POS"))).literalFor("STI")
        }
        shouldThrow<AggregateLiteralParseException> {
            MdePackAggregateLiterals("""{"aggregateDefaults":[{"aggregateType":"POS","businessTermId":" ","literal":"POS"}]}""")
                .literalFor("POS")
        }
    }
}
