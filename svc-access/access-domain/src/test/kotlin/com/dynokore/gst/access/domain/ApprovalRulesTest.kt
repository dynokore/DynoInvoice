package com.dynokore.gst.access.domain

import com.dynokore.gst.access.domain.approval.AckRef
import com.dynokore.gst.access.domain.approval.ApproverStanding
import com.dynokore.gst.access.domain.approval.Outstanding
import com.dynokore.gst.access.domain.approval.OverrideDenyReason
import com.dynokore.gst.access.domain.approval.OverrideDocument
import com.dynokore.gst.access.domain.approval.OverrideOutcome
import com.dynokore.gst.access.domain.approval.OverridePolicy
import com.dynokore.gst.access.domain.approval.OverrideReleaseOption
import com.dynokore.gst.access.domain.approval.OverrideReleaseRules
import com.dynokore.gst.access.domain.approval.OverrideRequest
import com.dynokore.gst.access.domain.approval.OverrideVerdict
import com.dynokore.gst.access.domain.approval.PackApprovalOption
import com.dynokore.gst.access.domain.approval.PackApprovalRules
import com.dynokore.gst.access.domain.approval.PackDenyReason
import com.dynokore.gst.access.domain.approval.PackVerdict
import com.dynokore.gst.access.domain.grant.IdentityRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The pure rules of INV-T-4936 (INV-Q-10695 R3 and R4) over every option, with the look-alikes that must not pass: the
 * person is compared by whole-string equality, a fact of another kind does not stand in for the one the option needs,
 * and an acknowledgement changes nothing under an option that does not use it.
 */
class ApprovalRulesTest {
    private val author = IdentityRef("IDN-A")
    private val other = IdentityRef("IDN-B")
    private val ack = AckRef("SOA-1")
    private val staff = ApproverStanding(staff = true, clientOwnApprover = true)
    private val external = ApproverStanding(externalOnEntity = true)
    private val agent = ApproverStanding(agentSecondPerson = true)

    private fun pack(
        option: PackApprovalOption,
        approver: IdentityRef,
        standing: ApproverStanding,
        activeAck: AckRef? = null,
        prior: Boolean = false,
    ) = PackApprovalRules.decide(option, author, approver, standing, activeAck, prior)

    private fun deny(reason: PackDenyReason) = PackVerdict.Deny(reason)

    @Test
    fun `INV-AC-2246 the staff options deny the author even with an acknowledgement active`() {
        listOf(PackApprovalOption.STAFF_APPROVER, PackApprovalOption.NON_FINANCE_OFFICER).forEach {
            assertEquals(deny(PackDenyReason.AUTHOR_IS_APPROVER), pack(it, author, staff))
            assertEquals(deny(PackDenyReason.AUTHOR_IS_APPROVER), pack(it, author, staff, ack))
            assertEquals(PackVerdict.Allow(), pack(it, other, staff))
            assertEquals(deny(PackDenyReason.NOT_STAFF), pack(it, other, external))
            assertEquals(deny(PackDenyReason.NOT_STAFF), pack(it, other, ApproverStanding()))
        }
    }

    @Test
    fun `INV-AC-1974 the advisor option wants an external grant on the entity and nothing else`() {
        val option = PackApprovalOption.EXTERNAL_ADVISOR
        assertEquals(PackVerdict.Allow(), pack(option, other, external))
        assertEquals(deny(PackDenyReason.NOT_EXTERNAL), pack(option, other, staff))
        assertEquals(deny(PackDenyReason.NOT_EXTERNAL), pack(option, other, ApproverStanding()))
        assertEquals(deny(PackDenyReason.AUTHOR_IS_APPROVER), pack(option, author, external))
    }

    @Test
    fun `INV-AC-9556 the author is admitted only under SOLE_OPERATOR_ACK with the acknowledgement active`() {
        val option = PackApprovalOption.SOLE_OPERATOR_ACK
        assertEquals(deny(PackDenyReason.NO_SOLE_OPERATOR_ACK), pack(option, author, staff))
        assertEquals(PackVerdict.Allow(soleOperatorAck = ack), pack(option, author, staff, ack))
        assertEquals(PackVerdict.Allow(), pack(option, other, staff, ack))
        assertEquals(PackVerdict.Allow(), pack(option, other, staff))
    }

    @Test
    fun `INV-AC-9557 the default agent path allows an agent second person and leaves the client acknowledgement`() {
        val option = PackApprovalOption.AGENT_SECOND_PERSON_CLIENT_ACK
        assertEquals(deny(PackDenyReason.AUTHOR_IS_APPROVER), pack(option, author, agent, ack))
        assertEquals(deny(PackDenyReason.NOT_AGENT_SECOND_PERSON), pack(option, other, ApproverStanding()))
        assertEquals(deny(PackDenyReason.NOT_AGENT_SECOND_PERSON), pack(option, other, staff))
        assertEquals(
            PackVerdict.Allow(outstanding = listOf(Outstanding.CLIENT_ACKNOWLEDGEMENT)),
            pack(option, other, agent),
        )
    }

    @Test
    fun `INV-AC-9560 under each agent option the author alone is refused`() {
        val own = ApproverStanding(clientOwnApprover = true)
        PackApprovalOption.entries.filter { it.name.startsWith("AGENT_") }.forEach {
            assertEquals(deny(PackDenyReason.AUTHOR_IS_APPROVER), pack(it, author, agent, ack))
        }
        assertEquals(
            deny(PackDenyReason.NOT_CLIENT_APPROVER),
            pack(PackApprovalOption.CLIENT_APPROVER_ALONE, author, agent, ack),
        )
        assertEquals(PackVerdict.Allow(), pack(PackApprovalOption.CLIENT_APPROVER_ALONE, other, own))
        // a person who is both the author and the client's own approver is never silently both
        assertEquals(
            deny(PackDenyReason.AUTHOR_IS_APPROVER),
            pack(PackApprovalOption.CLIENT_APPROVER_ALONE, author, own),
        )
    }

    @Test
    fun `INV-AC-2248 the client approval option waits for the client approver, who follows an agent person`() {
        val option = PackApprovalOption.AGENT_SECOND_PERSON_CLIENT_APPROVAL
        val own = ApproverStanding(clientOwnApprover = true)
        assertEquals(PackVerdict.Allow(outstanding = listOf(Outstanding.CLIENT_APPROVAL)), pack(option, other, agent))
        assertEquals(PackVerdict.Allow(), pack(option, other, own, prior = true))
        assertEquals(deny(PackDenyReason.NOT_AGENT_SECOND_PERSON), pack(option, other, own, prior = false))
    }

    private val one = OverridePolicy(OverrideReleaseOption.ONE_TAX_APPROVER, null)
    private val above = OverridePolicy(OverrideReleaseOption.SECOND_APPROVER_ABOVE_AMOUNT, BigDecimal("1000.00"))
    private val always = OverridePolicy(OverrideReleaseOption.SECOND_APPROVER_ALWAYS, null)

    @Suppress("LongParameterList") // test helper mirroring the rule's inputs
    private fun release(
        policy: OverridePolicy,
        total: String?,
        prior: List<IdentityRef> = emptyList(),
        approver: IdentityRef = author,
        overridable: Boolean = true,
        manages: Boolean = false,
        activeAck: AckRef? = null,
    ) = OverrideReleaseRules.decide(
        policy,
        OverrideRequest(
            overridable,
            total?.let { OverrideDocument("DOC-1", 1, BigDecimal(it)) },
            approver,
            prior,
            manages,
            activeAck,
        ),
    )

    private fun allow(
        outcome: OverrideOutcome,
        ackRef: AckRef? = null,
    ) = OverrideVerdict.Allow(outcome, ackRef)

    @Test
    fun `INV-AC-9561 the amount is compared as a number, at the threshold one approver suffices`() {
        assertEquals(allow(OverrideOutcome.RELEASED), release(above, "1000.00"))
        assertEquals(allow(OverrideOutcome.RELEASED), release(above, "1000"))
        assertEquals(allow(OverrideOutcome.AWAITS_SECOND_APPROVER), release(above, "1000.01"))
        assertEquals(deny(OverrideDenyReason.SAME_PERSON_TWICE), release(above, "1000.01", listOf(author)))
        assertEquals(allow(OverrideOutcome.RELEASED), release(above, "1000.01", listOf(other)))
    }

    private fun deny(reason: OverrideDenyReason) = OverrideVerdict.Deny(reason)

    @Test
    fun `INV-AC-2279 a code that is not overridable is denied before anything else`() {
        assertEquals(deny(OverrideDenyReason.NOT_OVERRIDABLE), release(one, "1.00", overridable = false))
        assertEquals(
            deny(OverrideDenyReason.NOT_OVERRIDABLE),
            release(always, "1.00", listOf(author), overridable = false, manages = true),
        )
        assertEquals(allow(OverrideOutcome.RELEASED), release(one, "1.00"))
        assertEquals(allow(OverrideOutcome.AWAITS_SECOND_APPROVER), release(always, "1.00"))
        assertEquals(allow(OverrideOutcome.RELEASED), release(always, "1.00", listOf(other)))
    }

    @Test
    fun `INV-Q-10721 a request without the document total waits under the amount option at any typed amount`() {
        assertEquals(allow(OverrideOutcome.AWAITS_SECOND_APPROVER), release(above, null))
        assertEquals(
            allow(OverrideOutcome.AWAITS_SECOND_APPROVER),
            release(OverridePolicy(OverrideReleaseOption.SECOND_APPROVER_ABOVE_AMOUNT, BigDecimal("999999")), null),
        )
        assertEquals(allow(OverrideOutcome.RELEASED), release(one, null))
        assertEquals(allow(OverrideOutcome.AWAITS_SECOND_APPROVER), release(always, null))
    }

    @Test
    fun `INV-AC-2042 an approver who also manages the override policy needs the acknowledgement`() {
        assertEquals(deny(OverrideDenyReason.NO_SOLE_OPERATOR_ACK), release(one, "1.00", manages = true))
        assertEquals(allow(OverrideOutcome.RELEASED, ack), release(one, "1.00", manages = true, activeAck = ack))
        // an acknowledgement does not mark an approver who does not manage the policy
        assertEquals(allow(OverrideOutcome.RELEASED), release(one, "1.00", activeAck = ack))
    }

    @Test
    fun `the amount belongs to the amount option and is above zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            OverridePolicy(OverrideReleaseOption.SECOND_APPROVER_ABOVE_AMOUNT, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OverridePolicy(OverrideReleaseOption.SECOND_APPROVER_ABOVE_AMOUNT, BigDecimal.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OverridePolicy(OverrideReleaseOption.ONE_TAX_APPROVER, BigDecimal.ONE)
        }
    }

    @Test
    fun `a person with a trailing space or another case is another person`() {
        assertEquals(PackVerdict.Allow(), pack(PackApprovalOption.STAFF_APPROVER, IdentityRef("IDN-A "), staff))
        assertEquals(PackVerdict.Allow(), pack(PackApprovalOption.STAFF_APPROVER, IdentityRef("idn-a"), staff))
        assertEquals(deny(OverrideDenyReason.SAME_PERSON_TWICE), release(above, "2000", listOf(IdentityRef("IDN-A"))))
        assertEquals(
            allow(OverrideOutcome.RELEASED),
            release(above, "2000", listOf(IdentityRef("IDN-A ")), IdentityRef("IDN-A")),
        )
    }
}
