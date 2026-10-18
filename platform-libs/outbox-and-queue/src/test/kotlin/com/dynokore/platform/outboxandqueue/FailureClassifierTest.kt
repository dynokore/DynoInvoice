package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.SQLTransientConnectionException

/** The default classifier keeps classing a transient database failure as infrastructure (INV-Q-3110, INV-Q-5279). */
class FailureClassifierTest {
    @Test
    fun `a transient connection failure with a cause is infrastructure`() {
        val failure =
            SQLTransientConnectionException("lane refused", "08001", java.io.IOException("pool exhausted"))
        assertEquals(FailureKind.INFRASTRUCTURE, FailureClassifier.DEFAULT.classify(failure))
    }

    @Test
    fun `a wrapped transient connection failure is infrastructure`() {
        val failure = IllegalStateException("handler failed", SQLTransientConnectionException("lost", "08006"))
        assertEquals(FailureKind.INFRASTRUCTURE, FailureClassifier.DEFAULT.classify(failure))
    }
}
