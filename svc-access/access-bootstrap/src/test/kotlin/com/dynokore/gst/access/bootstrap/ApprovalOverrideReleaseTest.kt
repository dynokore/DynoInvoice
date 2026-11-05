package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.OverrideReleaseDecision.Decision
import com.dynokore.gst.access.contract.model.OverrideReleaseDecision.Option
import com.dynokore.gst.access.contract.model.OverrideReleaseDecision.Outcome
import com.dynokore.gst.access.contract.model.OverrideReleaseDecision.ReasonCode
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * `decideOverrideRelease` over the real context and database (INV-T-4936; INV-Q-10695 R4, INV-Q-10721; INV-REQ-1293,
 * 500). The pinned catalogue lists VP-32 as `planned` and a planned row cannot be set, so these tests answer the
 * catalogue through [AvailableVp32]: the pinned catalogue with VP-32 alone raised to AVAILABLE (gate:
 * [AvailableVp32GateTest]).
 */
class ApprovalOverrideReleaseTest {
    private fun overrides(block: (ApprovalEnv) -> Unit) =
        approvalRun(
            configure = {
                it.withBean(
                    VariationPointCatalogue::class.java,
                    { AvailableVp32(GstVariationPointCatalogue("access-svc")) },
                )
            },
        ) { env ->
            env.seed("IDN-A1", Role.TAX_APPROVER, "ENT-1")
            env.seed("IDN-A2", Role.TAX_APPROVER, "ENT-1")
            block(env)
        }

    private fun ApprovalEnv.answer(
        approver: String = "IDN-A1",
        total: String? = "1.00",
        prior: List<String>? = null,
        code: String = "PER-001",
        withDocument: Boolean = true,
    ) = release(code = code, approver = approver, total = total, prior = prior, withDocument = withDocument).let {
        listOf(it.decision, it.outcome, it.reasonCode, it.option, it.soleOperator, it.soleOperatorAckRef)
    }

    private fun allow(
        outcome: Outcome,
        option: Option = Option.ONE_TAX_APPROVER,
        ackRef: String? = null,
    ) = listOf(Decision.ALLOW, outcome, null, option, ackRef != null, ackRef)

    private fun deny(
        reason: ReasonCode,
        option: Option = Option.ONE_TAX_APPROVER,
    ) = listOf(Decision.DENY, null, reason, option, false, null)

    private fun setAmount(
        env: ApprovalEnv,
        amount: String = "1000.00",
    ) = env.set("VP-32", "ENT-1", "SECOND_APPROVER_ABOVE_AMOUNT", value = amount)

    @Test
    fun `INV-AC-2279 by default an overridable code is released and any other code is refused`() =
        overrides { env ->
            assertThat(env.answer(code = "PER-001")).isEqualTo(allow(Outcome.RELEASED))
            listOf("PAY-001", "PAY-008", "PAY-0017", "pay-017", "per-001", "PER-001 ", "").forEach {
                assertThat(env.answer(code = it)).describedAs(it).isEqualTo(deny(ReasonCode.NOT_OVERRIDABLE))
            }
        }

    @Test
    fun `INV-AC-9561 above the amount a different second approver is needed, at the amount not`() =
        overrides { env ->
            env.accepted(setAmount(env))
            val option = Option.SECOND_APPROVER_ABOVE_AMOUNT
            val results =
                listOf(
                    env.answer(total = "1000.00"),
                    env.answer(total = "1000.01"),
                    env.answer(total = "1000.01", prior = listOf("IDN-A1")),
                    env.answer(approver = "IDN-A2", total = "1000.01", prior = listOf("IDN-A1")),
                )
            assertThat(results[0]).isEqualTo(allow(Outcome.RELEASED, option))
            assertThat(results[1]).isEqualTo(allow(Outcome.AWAITS_SECOND_APPROVER, option))
            assertThat(results[2]).isEqualTo(deny(ReasonCode.SAME_PERSON_TWICE, option))
            assertThat(results[3]).isEqualTo(allow(Outcome.RELEASED, option))
            // a threshold of 0 is refused at setPolicy, and so is a negative one and a text
            env.refused(setAmount(env, "0"))
            env.refused(setAmount(env, "0.00"))
            env.refused(setAmount(env, "-1"))
            env.refused(setAmount(env, "\"1000\""))
            assertThat(env.answer(total = "1000.00")).isEqualTo(allow(Outcome.RELEASED, option))
        }

    @Test
    fun `INV-AC-9561 a second approver always means the first approval waits`() =
        overrides { env ->
            env.accepted(env.set("VP-32", "ENT-1", "SECOND_APPROVER_ALWAYS"))
            val option = Option.SECOND_APPROVER_ALWAYS
            assertThat(env.answer(total = "0.01")).isEqualTo(allow(Outcome.AWAITS_SECOND_APPROVER, option))
            assertThat(env.answer(approver = "IDN-A2", total = "0.01", prior = listOf("IDN-A1")))
                .isEqualTo(allow(Outcome.RELEASED, option))
            // the value of entity 1 does not carry to entity 2
            assertThat(env.release(entity = "ENT-2").option).isEqualTo(Option.ONE_TAX_APPROVER)
        }

    @Test
    fun `INV-Q-10721 a request without the document waits under the amount option at any typed amount`() =
        overrides { env ->
            assertThat(env.answer(total = null, withDocument = false)).isEqualTo(allow(Outcome.RELEASED))
            env.accepted(setAmount(env))
            val option = Option.SECOND_APPROVER_ABOVE_AMOUNT
            assertThat(env.answer(total = null, withDocument = false))
                .isEqualTo(allow(Outcome.AWAITS_SECOND_APPROVER, option))
            env.accepted(setAmount(env, "999999999.99"))
            assertThat(env.answer(total = null, withDocument = false))
                .isEqualTo(allow(Outcome.AWAITS_SECOND_APPROVER, option))
        }

    @Test
    fun `INV-Q-10721 a request with some but not all of the document fields is a contract violation`() =
        overrides { env ->
            val onlyTotalAbsent =
                env.refusal {
                    env.asUser(ApprovalEnv.ADMIN) {
                        env.operations.decideOverrideRelease("ENT-1", "PER-001", "IDN-A1", "DOC-1", 1, null, null)
                    }
                }
            val onlyRefPresent =
                env.refusal {
                    env.asUser(ApprovalEnv.ADMIN) {
                        env.operations.decideOverrideRelease("ENT-1", "PER-001", "IDN-A1", "DOC-1", null, null, null)
                    }
                }
            val onlyTotalPresent =
                env.refusal {
                    env.asUser(ApprovalEnv.ADMIN) {
                        env.operations.decideOverrideRelease(
                            "ENT-1",
                            "PER-001",
                            "IDN-A1",
                            null,
                            null,
                            java.math.BigDecimal("1.00"),
                            null,
                        )
                    }
                }
            val tooPrecise = env.refusal { env.release(total = "1000.001") }
            listOf(onlyTotalAbsent, onlyRefPresent, onlyTotalPresent, tooPrecise).forEach {
                assertThat(it.code).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }

    @Test
    fun `INV-Q-10721 a total written with a thousands separator is a contract violation over HTTP`() =
        overrides { env ->
            val mvc =
                MockMvcBuilders
                    .webAppContextSetup(
                        env.context.sourceApplicationContext as WebApplicationContext,
                    ).build()

            fun status(total: String) =
                mvc
                    .perform(
                        MockMvcRequestBuilders
                            .get("/internal/v1/access/override-release-decision")
                            .queryParam("entityRef", "ENT-1")
                            .queryParam("errorCode", "PER-001")
                            .queryParam("approverRef", "IDN-A1")
                            .queryParam("documentRef", "DOC-1")
                            .queryParam("documentVersion", "1")
                            .queryParam("documentTotalSgd", total)
                            .accept(MediaType.APPLICATION_JSON, MediaType.valueOf("application/problem+json")),
                    ).andReturn()
                    .response
            val bad = status("1,000.00")
            assertThat(bad.status).isEqualTo(400)
            assertThat(bad.contentAsString).contains("SRT-001")
        }

    @Test
    fun `INV-AC-2042 an approver who manages the override policy needs the acknowledgement`() =
        overrides { env ->
            // the first administrator holds override-policy.manage (tenant.admin)
            val without = env.answer(approver = ApprovalEnv.ADMIN)
            val ack = env.acknowledge()
            val with = env.answer(approver = ApprovalEnv.ADMIN)
            val plain = env.answer(approver = "IDN-A1")
            assertThat(without).isEqualTo(deny(ReasonCode.NO_SOLE_OPERATOR_ACK))
            assertThat(with).isEqualTo(allow(Outcome.RELEASED, ackRef = ack))
            assertThat(plain).isEqualTo(allow(Outcome.RELEASED))
        }

    @Test
    fun `INV-AC-2042 a change of VP-32 or VP-03 by a holder of validation override needs the acknowledgement`() =
        overrides { env ->
            // a fixture: one person holds both roles without the acknowledgement, which grantRole would refuse
            env.seed("IDN-D", Role.TENANT_ADMIN)
            env.seed("IDN-D", Role.TAX_APPROVER, "ENT-1")
            env.refused(env.set("VP-03", "ENT-1", "EXTERNAL_ADVISOR", actor = "IDN-D"))
            env.refused(env.set("VP-32", "ENT-1", "SECOND_APPROVER_ALWAYS", actor = "IDN-D"))
            // the person who does not hold validation.override is not affected
            env.accepted(env.set("VP-03", "ENT-1", "EXTERNAL_ADVISOR"))
            env.acknowledge()
            env.accepted(env.set("VP-32", "ENT-1", "SECOND_APPROVER_ALWAYS", actor = "IDN-D"))
            env.accepted(env.set("VP-03", "ENT-1", "STAFF_APPROVER", actor = "IDN-D"))
            // each change by that person is audited with the sole-operator fact and the acknowledgement; none by the
            // administrator's plain change is
            val rows = soleOperatorAudit(env.tenant.value)
            assertThat(rows).hasSize(2)
            rows.forEach { assertThat(it).contains("IDN-D", "soleOperator=true", "soleOperatorAckRef=SOA-") }
        }

    private fun soleOperatorAudit(tenant: java.util.UUID): List<String> =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement("select data::text from access.outbox where tenant_id = ? and data::text like ?")
                .use { statement ->
                    statement.setObject(1, tenant)
                    statement.setString(2, "%setPolicy.soleOperator%")
                    statement.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
                }
        }
}
