package com.dynokore.gst.rules.adapters.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `activation.noDistinctApprover` in the answers of activateMappingPack and getMappingPack is the value stored with the
 * activation (INV-REQ-992), not derived from the approvals; a pack never activated reads false.
 */
class NoDistinctApproverTest {
    @Test
    fun `INV-AC-19525 a stored true reads true`() {
        MappingPackMapping.activationOf(true).noDistinctApprover shouldBe true
    }

    @Test
    fun `INV-AC-19525 a stored false reads false`() {
        MappingPackMapping.activationOf(false).noDistinctApprover shouldBe false
    }
}
