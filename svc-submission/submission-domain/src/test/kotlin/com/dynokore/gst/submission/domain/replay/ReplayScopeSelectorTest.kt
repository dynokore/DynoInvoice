package com.dynokore.gst.submission.domain.replay

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ReplayScopeSelectorTest {
    private val t0 = Instant.parse("2026-04-01T00:00:00Z")

    private fun cand(
        doc: String,
        version: Int = 1,
        entity: String = "E-001",
        period: String = "2026-Q1",
    ) = ReplayCandidate(
        doc, version, entity, period, LifecycleState.DEAD_LETTERED, "SUB-008", LocalDate.of(2026, 4, 30), t0,
    )

    private fun scope(
        entity: String = "E-001",
        period: String? = null,
        doc: String? = null,
        failure: String? = null,
    ) = ReplayScope(entity, period, doc, failure)

    private fun ids(s: ReplaySelection) = s.selected.map { it.docUuid to it.version }

    private val fixture =
        listOf(
            cand("d-q2", period = "2026-Q2").copy(dueDate = LocalDate.of(2026, 7, 31)),
            cand("d-q1b").copy(queuedAt = t0.plusSeconds(5)),
            cand("d-q1a").copy(dueDate = LocalDate.of(2026, 4, 15)),
            cand("d-unconfirmed").copy(state = LifecycleState.SENT_UNCONFIRMED),
            cand("d-other", entity = "E-002"),
        )

    @Test
    fun `INV-AC-13133 scope entity and period selects only that period dead-lettered in due-date order`() {
        val r = ReplayScopeSelector.select(fixture, scope(period = "2026-Q1"), 10)
        ids(r) shouldBe listOf("d-q1a" to 1, "d-q1b" to 1)
        r.truncated shouldBe false
    }

    @Test
    fun `INV-AC-13133 scope without a period selects both periods of the entity`() {
        val r = ReplayScopeSelector.select(fixture, scope(), 10)
        ids(r) shouldBe listOf("d-q1a" to 1, "d-q1b" to 1, "d-q2" to 1)
    }

    @Test
    fun `INV-AC-13133 selected versions are the candidates themselves so no new version or Doc UUID exists`() {
        val r = ReplayScopeSelector.select(fixture, scope(period = "2026-Q1"), 10)
        r.selected shouldBe listOf(fixture[2], fixture[1])
    }

    @Test
    fun `failure class matches by exact equality and never matches null`() {
        val cs =
            listOf(
                cand("a"),
                cand("b").copy(failureClass = "sub-008"),
                cand("c").copy(failureClass = "SUB-0080"),
                cand("d").copy(failureClass = null),
            )
        ids(ReplayScopeSelector.select(cs, scope(failure = "SUB-008"), 10)) shouldBe listOf("a" to 1)
    }

    @Test
    fun `doc uuid differing in the last character is not selected`() {
        val cs = listOf(cand("doc-1"), cand("doc-2"))
        ids(ReplayScopeSelector.select(cs, scope(doc = "doc-1"), 10)) shouldBe listOf("doc-1" to 1)
    }

    @Test
    fun `cap truncates after the order and reports it`() {
        val cs = listOf(cand("a"), cand("b"), cand("c"))
        val two = ReplayScopeSelector.select(cs, scope(), 2)
        ids(two) shouldBe listOf("a" to 1, "b" to 1)
        two.truncated shouldBe true
        val three = ReplayScopeSelector.select(cs, scope(), 3)
        ids(three).size shouldBe 3
        three.truncated shouldBe false
    }

    @Test
    fun `equal due dates are ordered by queuedAt`() {
        val cs = listOf(cand("a").copy(queuedAt = t0.plusSeconds(9)), cand("z"))
        ids(ReplayScopeSelector.select(cs, scope(), 10)) shouldBe listOf("z" to 1, "a" to 1)
    }

    @Test
    fun `equal queuedAt is ordered by doc uuid then version`() {
        val cs = listOf(cand("b", 1), cand("a", 2), cand("a", 1))
        ids(ReplayScopeSelector.select(cs, scope(), 10)) shouldBe listOf("a" to 1, "a" to 2, "b" to 1)
    }

    @Test
    fun `blank fields, a cap of zero and a duplicate are refused`() {
        shouldThrow<IllegalArgumentException> { scope(entity = " ") }
        shouldThrow<IllegalArgumentException> { scope(period = "") }
        shouldThrow<IllegalArgumentException> { scope(doc = " ") }
        shouldThrow<IllegalArgumentException> { scope(failure = "") }
        shouldThrow<IllegalArgumentException> { cand("a").copy(failureClass = " ") }
        shouldThrow<IllegalArgumentException> { cand("a", version = 0) }
        shouldThrow<IllegalArgumentException> { ReplayScopeSelector.select(listOf(cand("a")), scope(), 0) }
        shouldThrow<IllegalArgumentException> { ReplayScopeSelector.select(listOf(cand("a"), cand("a")), scope(), 5) }
    }
}
