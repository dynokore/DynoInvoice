package com.dynokore.gst.submission.domain.relay

import com.dynokore.gst.submission.domain.relay.RelayOutcomeDecision.Hold
import com.dynokore.gst.submission.domain.relay.RelayOutcomeDecision.MarkNotSubmittable
import com.dynokore.gst.submission.domain.relay.RelayOutcomeDecision.OrdinaryPath
import com.dynokore.gst.submission.domain.relay.RelayOutcomeDecision.SendNetworkDocument
import com.dynokore.gst.submission.domain.scope.NotSubmittableReason
import com.dynokore.gst.submission.domain.typing.NetworkPath
import com.dynokore.gst.submission.domain.typing.NetworkPath.ISSUED_VIA_NETWORK
import com.dynokore.gst.submission.domain.typing.NetworkPath.NOT_ON_NETWORK
import com.dynokore.gst.submission.domain.typing.NetworkPath.RECEIVED_VIA_NETWORK
import com.dynokore.gst.submission.domain.typing.NetworkPath.RELAY_ISSUANCE
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_1A
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_1B
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_2
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_3
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class RelayOutcomeRoutingTest {
    private val ready1a = PayloadOutcome.Ready(TYPE_1A)
    private val ready1b = PayloadOutcome.Ready(TYPE_1B)
    private val ready2 = PayloadOutcome.Ready(TYPE_2)
    private val ready3 = PayloadOutcome.Ready(TYPE_3)
    private val rejected = PayloadOutcome.Rejected
    private val refused = MarkNotSubmittable(NotSubmittableReason.ISSUANCE_REFUSED)
    private val missing = Hold(HoldReason.FACTS_MISSING)

    private val table: Map<Pair<NetworkPath?, PayloadOutcome>, RelayOutcomeDecision> =
        mapOf(
            (RELAY_ISSUANCE to ready1a) to SendNetworkDocument,
            (RELAY_ISSUANCE to ready1b) to OrdinaryPath,
            (RELAY_ISSUANCE to ready2) to OrdinaryPath,
            (RELAY_ISSUANCE to ready3) to OrdinaryPath,
            (RELAY_ISSUANCE to rejected) to refused,
            (ISSUED_VIA_NETWORK to ready1a) to OrdinaryPath,
            (ISSUED_VIA_NETWORK to ready1b) to OrdinaryPath,
            (ISSUED_VIA_NETWORK to ready2) to OrdinaryPath,
            (ISSUED_VIA_NETWORK to ready3) to OrdinaryPath,
            (ISSUED_VIA_NETWORK to rejected) to OrdinaryPath,
            (RECEIVED_VIA_NETWORK to ready1a) to OrdinaryPath,
            (RECEIVED_VIA_NETWORK to ready1b) to OrdinaryPath,
            (RECEIVED_VIA_NETWORK to ready2) to OrdinaryPath,
            (RECEIVED_VIA_NETWORK to ready3) to OrdinaryPath,
            (RECEIVED_VIA_NETWORK to rejected) to OrdinaryPath,
            (NOT_ON_NETWORK to ready1a) to OrdinaryPath,
            (NOT_ON_NETWORK to ready1b) to OrdinaryPath,
            (NOT_ON_NETWORK to ready2) to OrdinaryPath,
            (NOT_ON_NETWORK to ready3) to OrdinaryPath,
            (NOT_ON_NETWORK to rejected) to OrdinaryPath,
            (null to ready1a) to missing,
            (null to ready1b) to missing,
            (null to ready2) to missing,
            (null to ready3) to missing,
            (null to rejected) to missing,
        )

    private fun decide(
        path: NetworkPath?,
        outcome: PayloadOutcome,
    ) = RelayOutcomeRouting.decide(RelayOutcomeFacts(path, outcome))

    @Test
    fun `INV-AC-13335 the table lists every combination of 5 network paths and 5 outcomes`() {
        val paths = NetworkPath.entries + listOf<NetworkPath?>(null)
        val outcomes = listOf(ready1a, ready1b, ready2, ready3, rejected)
        table.keys shouldContainExactlyInAnyOrder paths.flatMap { p -> outcomes.map { p to it } }
        table.size shouldBe 25
    }

    @Test
    fun `INV-AC-13335 every combination gives the listed decision`() {
        table.forEach { (key, expected) -> decide(key.first, key.second) shouldBe expected }
    }

    @Test
    fun `INV-AC-13335 exactly two rows give neither OrdinaryPath nor Hold`() {
        table.values.filter { it != OrdinaryPath && it !is Hold } shouldContainExactlyInAnyOrder
            listOf(SendNetworkDocument, refused)
        table.values.count { it == OrdinaryPath } shouldBe 18
        table.values.count { it == missing } shouldBe 5
    }

    @Test
    fun `INV-AC-13335 absent facts give a hold`() {
        RelayOutcomeRouting.decide(null) shouldBe missing
    }

    @Test
    fun `INV-AC-13335 a type 1A document issued through the ERP own network is never sent`() {
        decide(ISSUED_VIA_NETWORK, ready1a) shouldBe OrdinaryPath
        decide(ISSUED_VIA_NETWORK, ready1a) shouldNotBe SendNetworkDocument
    }

    @Test
    fun `INV-AC-13335 a relay version of type 2 stays on the ordinary path`() {
        decide(RELAY_ISSUANCE, ready2) shouldBe OrdinaryPath
    }

    @Test
    fun `INV-AC-13335 the refusal carries the reason ISSUANCE_REFUSED`() {
        val decision = decide(RELAY_ISSUANCE, rejected) as MarkNotSubmittable
        decision.reason shouldBe NotSubmittableReason.ISSUANCE_REFUSED
        decision.reason.name shouldBe "ISSUANCE_REFUSED"
    }

    @Test
    fun `INV-AC-13335 decide takes exactly one RelayOutcomeFacts and the facts have two fields`() {
        val methods = RelayOutcomeRouting::class.java.declaredMethods.filter { it.name == "decide" }
        methods.size shouldBe 1
        methods.single().parameterTypes.toList() shouldBe listOf(RelayOutcomeFacts::class.java)
        RelayOutcomeFacts::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet() shouldBe
            setOf("networkPath", "outcome")
    }

    @Test
    fun `INV-AC-13335 the payload outcome has exactly the two known members`() {
        PayloadOutcome::class.java.permittedSubclasses
            .map { it.simpleName }
            .toSet() shouldBe setOf("Ready", "Rejected")
        HoldReason.entries.map { it.name } shouldBe listOf("FACTS_MISSING")
    }
}
