package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.application.MappingApproval
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * `activation.noDistinctApprover` in the answers of activateMappingPack and getMappingPack is true exactly when at
 * least one approval row of the pack has `sole_operator` true (INV-REQ-992).
 */
class NoDistinctApproverTest {
    private fun approval(soleOperator: Boolean) =
        MappingApproval(
            approverUserId = "user-b",
            at = Instant.parse("2026-01-01T00:00:00Z"),
            deltaId = "delta-1",
            soleOperator = soleOperator,
            soleOperatorAckRef = if (soleOperator) "ack-1" else null,
            option = "A",
            outstanding = emptySet(),
        )

    @Test
    fun `INV-REQ-992 no approval row leaves noDistinctApprover false`() {
        MappingPackMapping.activationOf(emptyList()).noDistinctApprover shouldBe false
    }

    @Test
    fun `INV-REQ-992 one approval row with sole_operator false leaves noDistinctApprover false`() {
        MappingPackMapping.activationOf(listOf(approval(false))).noDistinctApprover shouldBe false
    }

    @Test
    fun `INV-REQ-992 two approval rows of which one has sole_operator true make noDistinctApprover true`() {
        MappingPackMapping.activationOf(listOf(approval(false), approval(true))).noDistinctApprover shouldBe true
    }
}
