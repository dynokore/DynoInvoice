package com.dynokore.gst.canonical.bootstrap

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-AC-25342: the event catalogue (the emission guard's list) holds the six types and NetDerivationRefused. */
class NetRefusalCatalogueTest {
    private val prefix = "com.dynokore.gst.canonical."
    private val refused = "${prefix}NetDerivationRefused.v1"

    @Test
    fun `INV-AC-25342 topicByType equals the seven expected pairs`() {
        CanonicalEvents.topicByType shouldBe
            mapOf(
                "${prefix}TransactionRecorded.v1" to "gst.canonical.transaction-recorded.v1",
                "${prefix}CanonicalVersionCreated.v1" to "gst.canonical.canonical-version-created.v1",
                "${prefix}DecisionTraceRecorded.v1" to "gst.canonical.decision-trace-recorded.v1",
                "${prefix}DocumentHeld.v1" to "gst.canonical.document-held.v1",
                "${prefix}DocumentReleased.v1" to "gst.canonical.document-released.v1",
                "${prefix}PeriodReassigned.v1" to "gst.canonical.period-reassigned.v1",
                refused to "gst.canonical.net-derivation-refused.v1",
            )
    }

    @Test
    fun `INV-AC-25342 entries hold exactly one NetDerivationRefused entry on its topic`() {
        val matching = CanonicalEvents.entries.filter { it.eventType == refused }
        matching shouldHaveSize 1
        matching.single().topic shouldBe "gst.canonical.net-derivation-refused.v1"
    }

    @Test
    fun `INV-AC-25342 the look-alike versions and the consumed request type are absent`() {
        val types = CanonicalEvents.entries.map { it.eventType }
        types.contains("${prefix}NetDerivationRefused.v2") shouldBe false
        types.contains("${prefix}NetDerivationRequested.v1") shouldBe false
    }
}
