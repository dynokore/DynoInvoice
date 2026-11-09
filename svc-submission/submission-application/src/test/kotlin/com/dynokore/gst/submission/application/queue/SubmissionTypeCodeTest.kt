package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SubmissionTypeCodeTest {
    @Test
    fun `INV-AC-19465 the wire values 1A 1B 2 and 3 map to the four kinds`() {
        SubmissionTypeCode.fromWire("1A") shouldBe SubmissionKind.TYPE_1A
        SubmissionTypeCode.fromWire("1B") shouldBe SubmissionKind.TYPE_1B
        SubmissionTypeCode.fromWire("2") shouldBe SubmissionKind.TYPE_2
        SubmissionTypeCode.fromWire("3") shouldBe SubmissionKind.TYPE_3
    }

    @Test
    fun `INV-AC-19465 any other wire value is a contract violation`() {
        listOf("4", "1a", "TYPE_1A", " 1A", "").forEach { wire ->
            shouldThrow<ContractViolation> { SubmissionTypeCode.fromWire(wire) }
        }
    }
}
