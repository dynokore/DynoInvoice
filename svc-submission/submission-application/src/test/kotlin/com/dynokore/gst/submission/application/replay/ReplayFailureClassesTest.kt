package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.submission.domain.replay.ReplayScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The closed set of storable failure classes of a replay scope (INV-REQ-575, INV-AC-20586). */
class ReplayFailureClassesTest {
    private fun scope(failureClass: String?) = ReplayScope("E-001", null, null, failureClass)

    @Test
    fun `INV-AC-20586 V1 SUB-001 is accepted`() {
        shouldNotThrowAny { ReplayFailureClasses.validate(scope("SUB-001")) }
    }

    @Test
    fun `INV-AC-20586 V2 look-alikes are refused`() {
        listOf("sub-001", "SUB-0010", " SUB-001", "SUB-001 ", "SUB_001").forEach {
            shouldThrow<IllegalArgumentException> { ReplayFailureClasses.validate(scope(it)) }
        }
    }

    @Test
    fun `INV-AC-20586 V3 a well-formed code outside the set is refused`() {
        shouldThrow<IllegalArgumentException> { ReplayFailureClasses.validate(scope("SUB-999")) }
    }

    @Test
    fun `INV-AC-20586 V4 no class is accepted`() {
        shouldNotThrowAny { ReplayFailureClasses.validate(scope(null)) }
        ReplayFailureClasses.STORABLE shouldBe setOf("SUB-001")
    }
}
