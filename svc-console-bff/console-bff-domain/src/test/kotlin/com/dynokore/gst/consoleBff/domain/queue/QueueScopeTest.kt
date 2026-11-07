package com.dynokore.gst.consoleBff.domain.queue

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QueueScopeTest {
    private val scope = setOf("E-001", "E-002")

    private fun row(
        id: String,
        entity: String,
    ) = QueueRow(id, entity, "MDM-001", "Finance")

    @Test
    fun `INV-AC-15819 null request lists every scope entity sorted whatever the set order`() {
        decideScope(null, scope) shouldBe ScopeDecision.Entities(listOf("E-001", "E-002"))
        decideScope(null, linkedSetOf("E-002", "E-001")) shouldBe ScopeDecision.Entities(listOf("E-001", "E-002"))
    }

    @Test
    fun `INV-AC-15819 request inside the scope gives only that entity`() {
        decideScope("E-002", scope) shouldBe ScopeDecision.Entities(listOf("E-002"))
    }

    @Test
    fun `INV-AC-15819 request outside the scope is refused including case space and blank variants`() {
        for (v in listOf("E-003", "e-001", " E-001", "", "E-0011")) {
            decideScope(v, scope) shouldBe ScopeDecision.Refused(v)
        }
    }

    @Test
    fun `INV-AC-15819 empty scope gives no entity and refuses a request`() {
        decideScope(null, emptySet()) shouldBe ScopeDecision.Entities(emptyList())
        decideScope("E-001", emptySet()) shouldBe ScopeDecision.Refused("E-001")
    }

    @Test
    fun `INV-AC-15819 rows keep only scope entities in input order and unchanged`() {
        val rows = listOf(row("x1", "E-002"), row("x2", "E-003"), row("x3", "E-001"), row("x4", "E-003"))
        val kept = keepInScope(rows, scope)
        kept shouldBe listOf(rows[0], rows[2])
        kept.map { it.entityRef } shouldBe listOf("E-002", "E-001")
    }

    @Test
    fun `INV-AC-15819 prefix and case look-alike rows are not kept and empty scope keeps none`() {
        val rows = listOf(row("a", "E-0011"), row("b", "e-001"), row("c", " E-001"))
        keepInScope(rows, setOf("E-001")) shouldBe emptyList()
        keepInScope(listOf(row("a", "E-001")), emptySet()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-15819 thousand rows of one out-of-scope entity give an empty result`() {
        val rows = (1..1000).map { row("id$it", "E-003") }
        keepInScope(rows, scope) shouldBe emptyList()
    }
}
