package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** `access.break-glass.approval-window` (INV-Q-9109 A): ISO-8601, above zero, at most PT15M; else no start-up. */
class BreakGlassApprovalWindowTest {
    private fun window(value: String?): Duration? {
        var found: Duration? = null
        val base = BoundaryFixture.runner()
        val runner = if (value == null) base else base.withPropertyValues("access.break-glass.approval-window=$value")
        runner.run { context ->
            val failure = context.startupFailure
            if (failure == null) {
                found = context.getBean(BreakGlassApprovalWindow::class.java).value
            } else {
                assertThat(generateSequence(failure) { it.cause }.map { it.message.orEmpty() }.joinToString(" | "))
                    .contains("access.break-glass.approval-window")
            }
        }
        return found
    }

    @Test
    fun `PT15M, PT14M59S and the default start`() {
        assertThat(window("PT15M")).isEqualTo(Duration.ofMinutes(15))
        assertThat(window("PT14M59S")).isEqualTo(Duration.ofSeconds(14 * 60 + 59))
        assertThat(window(null)).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `PT15M1S, PT0S, a negative value, 15m and a blank value stop the start-up naming the setting`() {
        listOf("PT15M1S", "PT0S", "-PT1M", "PT-1M", "15m", "15", "abc").forEach {
            assertThat(window(it)).describedAs(it).isNull()
        }
    }
}
