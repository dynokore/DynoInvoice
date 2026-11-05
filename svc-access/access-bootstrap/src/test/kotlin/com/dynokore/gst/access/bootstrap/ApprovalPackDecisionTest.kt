package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.PackApprovalDecision.Decision
import com.dynokore.gst.access.contract.model.PackApprovalDecision.Option
import com.dynokore.gst.access.contract.model.PackApprovalDecision.Outstanding
import com.dynokore.gst.access.contract.model.PackApprovalDecision.ReasonCode
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.role.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * `decidePackApproval` over the real context and database (INV-T-4936; INV-Q-10695 R3; INV-REQ-860, 993, 995, 996,
 * 1264, 1265): the grants, the consent, the acknowledgement and the VP-03 / VP-04 values are read live at every
 * question, and each case compares the whole answer (decision, reason, option, sole-operator fact) with the table.
 */
class ApprovalPackDecisionTest {
    private val agentItems = setOf(ScopeItem.SUBMIT_INVOICE_DATA, ScopeItem.MAPPING_APPROVAL)

    private fun ApprovalEnv.answer(
        entity: String,
        author: String,
        approver: String,
        prior: List<String>? = null,
    ) = pack(entity, author, approver, prior).let {
        listOf(it.decision, it.reasonCode, it.option, it.soleOperator, it.soleOperatorAckRef, it.outstanding)
    }

    private fun deny(
        reason: ReasonCode,
        option: Option = Option.STAFF_APPROVER,
    ) = listOf(Decision.DENY, reason, option, false, null, emptySet<Outstanding>())

    private fun allow(
        option: Option = Option.STAFF_APPROVER,
        soleOperator: Boolean = false,
        ackRef: String? = null,
        outstanding: Set<Outstanding> = emptySet(),
    ) = listOf(Decision.ALLOW, null, option, soleOperator, ackRef, outstanding)

    @Test
    fun `INV-AC-2246 INV-AC-1908 INV-AC-1049 the default refuses the author and allows a different staff approver`() =
        approvalRun { env ->
            env.seed("IDN-U", Role.TAX_APPROVER, "ENT-1")
            env.seed("IDN-V", Role.TAX_APPROVER, "ENT-1")
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-U")).isEqualTo(deny(ReasonCode.AUTHOR_IS_APPROVER))
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-V")).isEqualTo(allow())
            // a recorded acknowledgement alone does not admit the author under the default option (INV-Q-10695 R3)
            env.acknowledge()
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-U")).isEqualTo(deny(ReasonCode.AUTHOR_IS_APPROVER))
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-V")).isEqualTo(allow())
        }

    @Test
    fun `INV-AC-2246 a person with a trailing space, another case or no grant is another person without standing`() =
        approvalRun { env ->
            env.seed("IDN-U", Role.TAX_APPROVER, "ENT-1")
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-U ")).isEqualTo(deny(ReasonCode.NOT_STAFF))
            assertThat(env.answer("ENT-1", "IDN-U", "idn-u")).isEqualTo(deny(ReasonCode.NOT_STAFF))
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-NOBODY")).isEqualTo(deny(ReasonCode.NOT_STAFF))
            // a grant on entity 2 gives no standing on entity 1
            env.seed("IDN-W", Role.TAX_APPROVER, "ENT-2")
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-W")).isEqualTo(deny(ReasonCode.NOT_STAFF))
        }

    @Test
    fun `INV-AC-2246 a different person with only an external or cross-tenant grant is not staff`() =
        approvalRun { env ->
            env.grant("IDN-EXT", "tax.approver", "entity:ENT-1", GrantRequest.GranteeKind.EXTERNAL)
            env.grant("IDN-CROSS", "tax.approver", "entity:ENT-1", GrantRequest.GranteeKind.CROSS_TENANT)
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-EXT")).isEqualTo(deny(ReasonCode.NOT_STAFF))
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-CROSS")).isEqualTo(deny(ReasonCode.NOT_STAFF))
        }

    @Test
    fun `INV-AC-1974 an external advisor approves the pack of entity A and nothing beyond it`() =
        approvalRun { env ->
            env.seed("IDN-S", Role.TAX_APPROVER, "ENT-1")
            env.seed("IDN-S", Role.TAX_APPROVER, "ENT-2")
            env.grant("IDN-X", "tax.approver", "entity:ENT-1", GrantRequest.GranteeKind.EXTERNAL)
            env.accepted(env.set("VP-03", "ENT-1", "EXTERNAL_ADVISOR"))
            env.accepted(env.set("VP-03", "ENT-2", "EXTERNAL_ADVISOR"))
            val advisor = Option.EXTERNAL_ADVISOR
            assertThat(env.answer("ENT-1", "IDN-S", "IDN-X")).isEqualTo(allow(advisor))
            assertThat(env.answer("ENT-2", "IDN-S", "IDN-X")).isEqualTo(deny(ReasonCode.NOT_EXTERNAL, advisor))
            assertThat(env.answer("ENT-1", "IDN-X", "IDN-S")).isEqualTo(deny(ReasonCode.NOT_EXTERNAL, advisor))
            assertThat(env.answer("ENT-1", "IDN-S", "IDN-S")).isEqualTo(deny(ReasonCode.AUTHOR_IS_APPROVER, advisor))
        }

    @Test
    fun `INV-AC-1974 look-alikes of the external advisor are refused`() =
        approvalRun { env ->
            env.grant("IDN-CROSS", "tax.approver", "entity:ENT-1", GrantRequest.GranteeKind.CROSS_TENANT)
            env.grant("IDN-WIDE", "tax.approver", "tenant:${env.tenant}", GrantRequest.GranteeKind.EXTERNAL)
            env.accepted(env.set("VP-03", "ENT-1", "EXTERNAL_ADVISOR"))
            val advisor = Option.EXTERNAL_ADVISOR
            assertThat(env.answer("ENT-1", "IDN-S", "IDN-CROSS")).isEqualTo(deny(ReasonCode.NOT_EXTERNAL, advisor))
            // an external grant of the whole tenant is not a grant scoped to the entity
            assertThat(env.answer("ENT-1", "IDN-S", "IDN-WIDE")).isEqualTo(deny(ReasonCode.NOT_EXTERNAL, advisor))
        }

    @Test
    fun `INV-AC-9554 the advisor approves on the last valid day and no longer once the grant has expired`() =
        approvalRun { env ->
            val validTo = LocalDate.now().plusDays(60)
            env.grant("IDN-X", "tax.approver", "entity:ENT-1", GrantRequest.GranteeKind.EXTERNAL, validTo)
            env.accepted(env.set("VP-03", "ENT-1", "EXTERNAL_ADVISOR"))
            val noon =
                validTo
                    .minusDays(1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .plusSeconds(43_200)
            env.clock.advance(Duration.between(env.clock.instant(), noon))
            val onTheLastDay = env.answer("ENT-1", "IDN-S", "IDN-X")
            env.clock.advance(Duration.ofDays(2))
            val afterwards = env.answer("ENT-1", "IDN-S", "IDN-X")
            assertThat(onTheLastDay).isEqualTo(allow(Option.EXTERNAL_ADVISOR))
            assertThat(afterwards).isEqualTo(deny(ReasonCode.NOT_EXTERNAL, Option.EXTERNAL_ADVISOR))
        }

    @Test
    fun `INV-AC-9556 INV-AC-9555 the sole-operator option admits the author while the acknowledgement is active`() =
        approvalRun { env ->
            env.seed("IDN-U", Role.TAX_APPROVER, "ENT-1")
            env.seed("IDN-V", Role.TAX_APPROVER, "ENT-1")
            env.accepted(env.setLooser("VP-03", "ENT-1", "SOLE_OPERATOR_ACK"))
            val option = Option.SOLE_OPERATOR_ACK
            val before = env.answer("ENT-1", "IDN-U", "IDN-U")
            val ack = env.acknowledge()
            // the acknowledgement goes only while a second person holds tenant.admin (INV-AC-8288 case C7)
            env.grant("IDN-Z", "tenant.admin", "tenant:${env.tenant}")
            val during = env.answer("ENT-1", "IDN-U", "IDN-U")
            val p1 = env.answer("ENT-1", "IDN-U", "IDN-U")
            val p3 = env.answer("ENT-1", "IDN-V", "IDN-U")
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
            val after = env.answer("ENT-1", "IDN-U", "IDN-U")
            assertThat(before).isEqualTo(deny(ReasonCode.NO_SOLE_OPERATOR_ACK, option))
            assertThat(during).isEqualTo(allow(option, true, ack))
            assertThat(after).isEqualTo(deny(ReasonCode.NO_SOLE_OPERATOR_ACK, option))
            // the fact is carried by the approvals of the author (P1, P2) and not by a distinct approver's (P3)
            assertThat(listOf(p1, p1, p3).map { it[3] }).containsExactly(true, true, false)
            assertThat(p3).isEqualTo(allow(option))
        }

    @Test
    fun `INV-AC-9556 the value of entity B is not read for entity A and a look-alike option key is refused`() =
        approvalRun { env ->
            env.seed("IDN-U", Role.TAX_APPROVER, "ENT-1")
            env.seed("IDN-U", Role.TAX_APPROVER, "ENT-2")
            env.accepted(env.setLooser("VP-03", "ENT-2", "SOLE_OPERATOR_ACK"))
            env.acknowledge()
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-U")).isEqualTo(deny(ReasonCode.AUTHOR_IS_APPROVER))
            assertThat(env.answer("ENT-2", "IDN-U", "IDN-U")[2]).isEqualTo(Option.SOLE_OPERATOR_ACK)
            env.refused(env.set("VP-03", "ENT-1", "SOLE_OPERATOR_ACKS"))
            env.refused(env.set("VP-03", "ENT-1", "sole_operator_ack"))
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-U")[2]).isEqualTo(Option.STAFF_APPROVER)
        }

    @Test
    fun `INV-AC-9556 an acknowledgement of another tenant does not admit the author`() =
        approvalRun { env ->
            env.seed("IDN-U", Role.TAX_APPROVER, "ENT-1")
            env.accepted(env.setLooser("VP-03", "ENT-1", "SOLE_OPERATOR_ACK"))
            // the acknowledgement recorded in tenant B: the row policy hides it from the question bound to tenant A
            val other =
                ApprovalEnv(
                    env.context,
                    com.dynokore.platform.tenancykernel
                        .TenantId(UUID.randomUUID()),
                )
            other.acknowledge()
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-U"))
                .isEqualTo(deny(ReasonCode.NO_SOLE_OPERATOR_ACK, Option.SOLE_OPERATOR_ACK))
        }

    /** The client CLI-1 under a live consent that holds MAPPING_APPROVAL: P1 authored, P2 and P3 are agent persons. */
    private fun agentSetUp(env: ApprovalEnv) {
        env.consents.grant(EntityRef("CLI-1"), agentItems, agent = env.tenant.toString())
        env.seedConsentDerived("IDN-P1", Role.TAX_APPROVER)
        env.seedConsentDerived("IDN-P2", Role.TAX_APPROVER)
        env.seedConsentDerived("IDN-P3", Role.AGENT_OPERATOR)
        env.seed("IDN-Q", Role.TAX_APPROVER, "CLI-1")
        env.seed("IDN-S", Role.CLIENT_SIGNATORY, "CLI-1")
    }

    @Test
    fun `INV-AC-2248 INV-AC-1050 INV-AC-9557 the agent default needs a second agent person and the client`() =
        approvalRun { env ->
            agentSetUp(env)
            val option = Option.AGENT_SECOND_PERSON_CLIENT_ACK
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P1")).isEqualTo(deny(ReasonCode.AUTHOR_IS_APPROVER, option))
            assertThat(
                env.answer("CLI-1", "IDN-P1", "IDN-P3"),
            ).isEqualTo(deny(ReasonCode.NOT_AGENT_SECOND_PERSON, option))
            assertThat(
                env.answer("CLI-1", "IDN-P1", "IDN-Q"),
            ).isEqualTo(deny(ReasonCode.NOT_AGENT_SECOND_PERSON, option))
            val outstanding = setOf(Outstanding.CLIENT_ACKNOWLEDGEMENT)
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P2")).isEqualTo(allow(option, outstanding = outstanding))
            // the sole-operator path is never offered: an active acknowledgement changes nothing
            env.acknowledge()
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P1")).isEqualTo(deny(ReasonCode.AUTHOR_IS_APPROVER, option))
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P2")).isEqualTo(allow(option, outstanding = outstanding))
        }

    @Test
    fun `INV-AC-9557 a withdrawn consent returns the client entity to the value of VP-03`() =
        approvalRun { env ->
            agentSetUp(env)
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P2")[2]).isEqualTo(Option.AGENT_SECOND_PERSON_CLIENT_ACK)
            env.consents.withdraw(EntityRef("CLI-1"))
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P2")).isEqualTo(deny(ReasonCode.NOT_STAFF))
            // a consent without MAPPING_APPROVAL does not put the client under VP-04 either
            env.consents.grant(EntityRef("CLI-1"), setOf(ScopeItem.SUBMIT_INVOICE_DATA), agent = env.tenant.toString())
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P2")[2]).isEqualTo(Option.STAFF_APPROVER)
        }

    @Test
    fun `INV-AC-9560 the client signatory chooses the agent option and under each the author alone is refused`() =
        approvalRun { env ->
            agentSetUp(env)
            val read = env.answer("CLI-1", "IDN-P1", "IDN-P1")[2]
            val byAuthor = env.set("VP-04", "CLI-1", "CLIENT_APPROVER_ALONE", actor = "IDN-P1", level = "CLIENT_ENTITY")
            val bySignatory =
                env.set(
                    "VP-04",
                    "CLI-1",
                    "CLIENT_APPROVER_ALONE",
                    actor = "IDN-S",
                    level = "CLIENT_ENTITY",
                )
            assertThat(read).isEqualTo(Option.AGENT_SECOND_PERSON_CLIENT_ACK)
            env.refused(byAuthor)
            env.accepted(bySignatory)
            val one = mutableListOf<List<Any?>>()
            val agentOptions = Option.entries.filter { it.name.startsWith("AGENT_") || it.name.startsWith("CLIENT_") }
            agentOptions.forEach { option ->
                env.accepted(env.set("VP-04", "CLI-1", option.name, actor = "IDN-S", level = "CLIENT_ENTITY"))
                one += env.answer("CLI-1", "IDN-P1", "IDN-P1")
            }
            assertThat(one.map { it[0] }).containsOnly(Decision.DENY)
            assertThat(one.map { it[1] }).containsOnly(ReasonCode.AUTHOR_IS_APPROVER, ReasonCode.NOT_CLIENT_APPROVER)
            assertThat(one).hasSize(3)
        }

    @Test
    fun `INV-AC-9560 CLIENT_APPROVER_ALONE allows only the client's own approver`() =
        approvalRun { env ->
            agentSetUp(env)
            env.accepted(env.set("VP-04", "CLI-1", "CLIENT_APPROVER_ALONE", actor = "IDN-S", level = "CLIENT_ENTITY"))
            val option = Option.CLIENT_APPROVER_ALONE
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-Q")).isEqualTo(allow(option))
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P1")).isEqualTo(deny(ReasonCode.NOT_CLIENT_APPROVER, option))
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P2")).isEqualTo(deny(ReasonCode.NOT_CLIENT_APPROVER, option))
            assertThat(env.answer("CLI-1", "IDN-Q", "IDN-Q")).isEqualTo(deny(ReasonCode.AUTHOR_IS_APPROVER, option))
        }

    @Test
    fun `INV-AC-2248 the client approval option leaves the approval outstanding until the client's approver`() =
        approvalRun { env ->
            agentSetUp(env)
            val chosen = "AGENT_SECOND_PERSON_CLIENT_APPROVAL"
            env.accepted(env.set("VP-04", "CLI-1", chosen, actor = "IDN-S", level = "CLIENT_ENTITY"))
            val option = Option.AGENT_SECOND_PERSON_CLIENT_APPROVAL
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-P2"))
                .isEqualTo(allow(option, outstanding = setOf(Outstanding.CLIENT_APPROVAL)))
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-Q", listOf("IDN-P2"))).isEqualTo(allow(option))
            // the client's approver alone, or after a person who is no agent second person, is not two people
            assertThat(
                env.answer("CLI-1", "IDN-P1", "IDN-Q"),
            ).isEqualTo(deny(ReasonCode.NOT_AGENT_SECOND_PERSON, option))
            assertThat(env.answer("CLI-1", "IDN-P1", "IDN-Q", listOf("IDN-P3")))
                .isEqualTo(deny(ReasonCode.NOT_AGENT_SECOND_PERSON, option))
        }

    @Test
    fun `INV-AC-9559 the option defaults to a staff approver, is set by tenant admin and stays on its entity`() =
        approvalRun { env ->
            env.seed("IDN-X", Role.TAX_APPROVER, "ENT-1")
            val first = env.answer("ENT-1", "IDN-U", "IDN-V")[2]
            val byApprover = env.set("VP-03", "ENT-1", "EXTERNAL_ADVISOR", actor = "IDN-X")
            val byAdmin = env.set("VP-03", "ENT-1", "EXTERNAL_ADVISOR")
            val onA = env.answer("ENT-1", "IDN-U", "IDN-V")[2]
            val onB = env.answer("ENT-2", "IDN-U", "IDN-V")[2]
            env.refused(byApprover)
            env.accepted(byAdmin)
            assertThat(listOf(first, onA, onB))
                .containsExactly(Option.STAFF_APPROVER, Option.EXTERNAL_ADVISOR, Option.STAFF_APPROVER)
        }

    @Test
    fun `INV-AC-9559 INV-AC-2247 the looser sole-operator option needs the acknowledgement of the shared path`() =
        approvalRun { env ->
            env.refused(env.set("VP-03", "ENT-1", "SOLE_OPERATOR_ACK"))
            assertThat(env.answer("ENT-1", "IDN-U", "IDN-V")[2]).isEqualTo(Option.STAFF_APPROVER)
        }
}
