package com.dynokore.gst.submission.application.completeness

import com.dynokore.gst.submission.domain.readiness.CompletenessState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The completeness derivation over stored rows (INV-AC-22204). */
class StoredCompletenessTest {
    private fun row(
        id: String,
        state: StoredState,
    ) = StoredRow(id, state)

    @Test
    fun `INV-AC-22204 failing rows give Failed with the distinct identifiers in ascending order`() {
        StoredCompleteness.derive(
            listOf(
                row("window:P", StoredState.FAILING),
                row("feed:F1/VALUE", StoredState.FAILING),
                row("feed:F1/COUNT", StoredState.FAILING),
                row("feed:F1/COUNT", StoredState.FAILING),
            ),
        ) shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT", "feed:F1/VALUE", "window:P"))
    }

    @Test
    fun `INV-AC-22204 no row is Unavailable and never Passed`() {
        StoredCompleteness.derive(emptyList()) shouldBe CompletenessState.Unavailable
    }

    @Test
    fun `INV-AC-22204 an open part is Unavailable`() {
        StoredCompleteness.derive(
            listOf(row("feed:F1/COUNT", StoredState.NOT_FAILING), row("window:P", StoredState.OPEN)),
        ) shouldBe CompletenessState.Unavailable
    }

    @Test
    fun `INV-AC-22204 at least one row, none failing and none open is Passed`() {
        StoredCompleteness.derive(
            listOf(row("feed:F1/COUNT", StoredState.NOT_FAILING), row("window:P", StoredState.NOT_FAILING)),
        ) shouldBe CompletenessState.Passed
    }

    @Test
    fun `INV-AC-22204 a failing row wins over an open one`() {
        StoredCompleteness.derive(
            listOf(row("window:P", StoredState.OPEN), row("feed:F1/COUNT", StoredState.FAILING)),
        ) shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT"))
    }

    @Test
    fun `INV-AC-22204 the look-alike identifier with a trailing space is a different identifier`() {
        StoredCompleteness.derive(
            listOf(row("feed:F1/COUNT", StoredState.FAILING), row("feed:F1/COUNT ", StoredState.FAILING)),
        ) shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT", "feed:F1/COUNT "))
    }

    @Test
    fun `INV-AC-22204 identifiers are built from the feed, control and prescribed period`() {
        ControlIds.feed("F1", "COUNT") shouldBe "feed:F1/COUNT"
        ControlIds.window("P") shouldBe "window:P"
    }
}
