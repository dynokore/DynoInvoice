package com.dynokore.gst.consoleBff.domain.profile

import com.dynokore.gst.consoleBff.domain.profile.ProfileApplication.Apply
import com.dynokore.gst.consoleBff.domain.profile.ProfileApplication.Refused
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProfileConsentGateTest {
    private val files = ProposedProfile("PROF-AGENT-FILES", 1)
    private val filesValues =
        listOf(
            ProposedValue("VP-10", true),
            ProposedValue("VP-11", true),
            ProposedValue("VP-12", true),
            ProposedValue("VP-04", false),
        )
    private val submits = ProposedProfile("PROF-AGENT-SUBMITS", 1)
    private val submitsValues =
        listOf(ProposedValue("VP-11", true), ProposedValue("VP-10", true), ProposedValue("VP-70", false))

    private fun directAndNoProposal(
        profile: ProposedProfile,
        values: List<ProposedValue>,
    ) {
        for (p in listOf<ProposedProfile?>(profile, null)) {
            for (c in listOf(true, false)) {
                decideProfileApplication(ApplyOrigin.AGENT_DIRECT, p, values, c) shouldBe
                    Refused(RefusalReason.DIRECT_APPLICATION)
            }
        }
        for (c in listOf(true, false)) {
            decideProfileApplication(ApplyOrigin.SIGNATORY_CONSENT, null, values, c) shouldBe
                Refused(RefusalReason.NO_PROPOSAL)
        }
        decideProfileApplication(ApplyOrigin.SIGNATORY_CONSENT, profile, values, false) shouldBe
            Refused(RefusalReason.NO_CONSENT)
    }

    @Test
    fun `INV-AC-16085 agent-files refusals`() = directAndNoProposal(files, filesValues)

    @Test
    fun `INV-AC-16085 agent-files applies exactly the client-entity values on consent`() {
        decideProfileApplication(ApplyOrigin.SIGNATORY_CONSENT, files, filesValues, true) shouldBe
            Apply(
                files,
                listOf(ProposedValue("VP-10", true), ProposedValue("VP-11", true), ProposedValue("VP-12", true)),
            )
    }

    @Test
    fun `INV-AC-16086 agent-submits refusals`() = directAndNoProposal(submits, submitsValues)

    @Test
    fun `INV-AC-16086 agent-submits applies client-entity values in input order`() {
        decideProfileApplication(ApplyOrigin.SIGNATORY_CONSENT, submits, submitsValues, true) shouldBe
            Apply(submits, listOf(ProposedValue("VP-11", true), ProposedValue("VP-10", true)))
    }

    @Test
    fun `no client-entity value and empty list give an empty application`() {
        val none = listOf(ProposedValue("VP-04", false))
        decideProfileApplication(ApplyOrigin.SIGNATORY_CONSENT, files, none, true) shouldBe Apply(files, emptyList())
        decideProfileApplication(ApplyOrigin.SIGNATORY_CONSENT, files, emptyList(), true) shouldBe
            Apply(files, emptyList())
    }

    @Test
    fun `later change of the source list does not change the result`() {
        val source = filesValues.toMutableList()
        val result = decideProfileApplication(ApplyOrigin.SIGNATORY_CONSENT, files, source, true) as Apply
        source.add(ProposedValue("VP-99", true))
        result.values shouldBe
            listOf(ProposedValue("VP-10", true), ProposedValue("VP-11", true), ProposedValue("VP-12", true))
        source.size shouldBe 5
    }

    @Test
    fun `invalid arguments are refused`() {
        shouldThrow<IllegalArgumentException> { ProposedProfile(" ", 1) }
        shouldThrow<IllegalArgumentException> { ProposedProfile("", 1) }
        shouldThrow<IllegalArgumentException> { ProposedProfile("P", 0) }
        shouldThrow<IllegalArgumentException> { ProposedProfile("P", -1) }
        shouldThrow<IllegalArgumentException> { ProposedValue(" ", true) }
    }

    @Test
    fun `duplicate variation point is refused for every origin`() {
        val dup = listOf(ProposedValue("VP-10", true), ProposedValue("VP-10", false))
        for (o in ApplyOrigin.entries) {
            shouldThrow<IllegalArgumentException> { decideProfileApplication(o, files, dup, true) }
            shouldThrow<IllegalArgumentException> { decideProfileApplication(o, null, dup, false) }
        }
    }

    @Test
    fun `enum constants are fixed and ordered`() {
        ApplyOrigin.entries shouldBe listOf(ApplyOrigin.AGENT_DIRECT, ApplyOrigin.SIGNATORY_CONSENT)
        RefusalReason.entries shouldBe
            listOf(RefusalReason.DIRECT_APPLICATION, RefusalReason.NO_PROPOSAL, RefusalReason.NO_CONSENT)
    }
}
