package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-7193: the EndpointId rules (INV-REQ-321, INV-Q-7189 A), the first that applies wins. */
class EndpointIdTest {
    private fun construct(
        onNetwork: Boolean?,
        type: SubmissionType,
        scheme: String = "SG_UEN",
        prefix: String? = "PFX",
    ) = EndpointId.construct(onNetwork, type, scheme, "201888888A", prefix)

    @Test
    fun `EndpointId a scheme other than SG_UEN gives NoUenToAppend with onNetwork true, false and null`() {
        for (scheme in listOf("FOREIGN_REG", "NONE", "sg_uen", "SG_UEN ", "")) {
            for (onNetwork in listOf(true, false, null)) {
                for (type in SubmissionType.entries) {
                    construct(onNetwork, type, scheme) shouldBe EndpointIdResult.NoUenToAppend
                }
            }
        }
    }

    @Test
    fun `EndpointId onNetwork null gives ReachabilityUnknown`() {
        for (type in SubmissionType.entries) construct(null, type) shouldBe EndpointIdResult.ReachabilityUnknown
    }

    @Test
    fun `EndpointId on-network is SGUEN plus the value for every type, with or without a prefix`() {
        for (type in SubmissionType.entries) {
            construct(true, type) shouldBe EndpointIdResult.Built("SGUEN201888888A")
            construct(true, type, prefix = null) shouldBe EndpointIdResult.Built("SGUEN201888888A")
        }
    }

    @Test
    fun `EndpointId off-network TYPE_2 and TYPE_3 give the prefix plus the value`() {
        for (type in listOf(SubmissionType.TYPE_2, SubmissionType.TYPE_3)) {
            construct(false, type) shouldBe EndpointIdResult.Built("PFX201888888A")
        }
    }

    @Test
    fun `EndpointId off-network TYPE_1A and TYPE_1B give Unpublished`() {
        for (type in listOf(SubmissionType.TYPE_1A, SubmissionType.TYPE_1B)) {
            construct(false, type) shouldBe EndpointIdResult.Unpublished(type)
            construct(false, type, prefix = null) shouldBe EndpointIdResult.Unpublished(type)
        }
    }

    @Test
    fun `EndpointId off-network TYPE_2 and TYPE_3 with a null prefix give PrefixNotConfigured`() {
        for (type in listOf(SubmissionType.TYPE_2, SubmissionType.TYPE_3)) {
            construct(false, type, prefix = null) shouldBe EndpointIdResult.PrefixNotConfigured
        }
    }

    @Test
    fun `EndpointId table of all 3 x 4 combinations of onNetwork and submission type`() {
        val p = "PFX201888888A"
        val s = "SGUEN201888888A"
        val unknown = EndpointIdResult.ReachabilityUnknown
        val expected =
            mapOf(
                (true to SubmissionType.TYPE_1A) to EndpointIdResult.Built(s),
                (true to SubmissionType.TYPE_1B) to EndpointIdResult.Built(s),
                (true to SubmissionType.TYPE_2) to EndpointIdResult.Built(s),
                (true to SubmissionType.TYPE_3) to EndpointIdResult.Built(s),
                (false to SubmissionType.TYPE_1A) to EndpointIdResult.Unpublished(SubmissionType.TYPE_1A),
                (false to SubmissionType.TYPE_1B) to EndpointIdResult.Unpublished(SubmissionType.TYPE_1B),
                (false to SubmissionType.TYPE_2) to EndpointIdResult.Built(p),
                (false to SubmissionType.TYPE_3) to EndpointIdResult.Built(p),
                (null to SubmissionType.TYPE_1A) to unknown,
                (null to SubmissionType.TYPE_1B) to unknown,
                (null to SubmissionType.TYPE_2) to unknown,
                (null to SubmissionType.TYPE_3) to unknown,
            )
        expected.size shouldBe 12
        for ((key, result) in expected) construct(key.first, key.second) shouldBe result
    }
}
