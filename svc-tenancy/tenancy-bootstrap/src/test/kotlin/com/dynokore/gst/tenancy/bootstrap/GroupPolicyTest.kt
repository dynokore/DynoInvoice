package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.status.GoLiveStatusDecision
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.domain.AnswerSource
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.GroupTransmissionMode
import com.dynokore.gst.tenancy.domain.GroupTransmissionRules
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * The group-level variation points tenancy-svc owns: the election to transmit an excluded member (VP-64), the
 * implementation date (VP-51, in ObligationStartTest) and the group transmission mode (VP-28, planned: the default
 * only). They are set through config-resolver's writer, the operation the shared setPolicy mounted by service-runtime
 * fronts, and read through PolicyReader (INV-Q-8729 A, INV-Q-8735 A).
 */
class GroupPolicyTest {
    private val vp64 = "VP-64"
    private val vp28 = "VP-28"

    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun excluded(
        env: ProfileEnv,
        entity: EntityResponse,
        status: Excl,
    ) = env.set(
        entity,
        SetEntityStatusRequest(
            SetEntityStatusRequest.StatusKind.EXCLUDED_BUSINESS,
            day("2026-01-01"),
            excludedBusinessStatus = status,
            basis = "determination",
            evidenceRef = "EVID-1",
        ),
    )

    private fun transmit(
        env: ProfileEnv,
        group: com.dynokore.gst.tenancy.contract.model.GroupResponse,
    ) = env.setPolicy(vp64, ScopeLevel.GROUP, group, null, "TRANSMIT_AND_GO_LIVE")

    @Test
    fun `INV-AC-759 INV-AC-8590 with the election at its default an excluded member does not go live`() {
        run { env ->
            env.withGroup(2) { _, _, members ->
                val (member, other) = members
                excluded(env, member, Excl.OVR)
                excluded(env, other, Excl.NOT_EXCLUDED)
                assertThat(env.guard.checkStatus(member.entityRef, TODAY))
                    .isEqualTo(GoLiveStatusDecision.Refused(ExcludedBusinessStatus.OVR))
                assertThat(env.guard.checkStatus(other.entityRef, TODAY))
                    .isEqualTo(GoLiveStatusDecision.Allowed(ExcludedBusinessStatus.NOT_EXCLUDED))
            }
        }
    }

    @Test
    fun `INV-AC-1554 INV-AC-8590 with the group electing to transmit the excluded member goes live`() {
        run { env ->
            env.withGroup(2) { _, group, members ->
                val (member, other) = members
                excluded(env, member, Excl.RC_ONLY)
                excluded(env, other, Excl.NOT_EXCLUDED)
                transmit(env, group)
                assertThat(env.guard.checkStatus(member.entityRef, TODAY))
                    .isEqualTo(GoLiveStatusDecision.Allowed(ExcludedBusinessStatus.RC_ONLY))
                assertThat(env.guard.checkStatus(other.entityRef, TODAY))
                    .isEqualTo(GoLiveStatusDecision.Allowed(ExcludedBusinessStatus.NOT_EXCLUDED))
            }
        }
    }

    @Test
    fun `INV-AC-2313 INV-AC-8594 an excluded member cannot go live while the group omits its data, then it can`() {
        run { env ->
            env.withGroup(1) { _, group, members ->
                val member = members.single()
                excluded(env, member, Excl.OVERSEAS_S33_1)
                assertThat(env.guard.checkStatus(member.entityRef, TODAY))
                    .isEqualTo(GoLiveStatusDecision.Refused(ExcludedBusinessStatus.OVERSEAS_S33_1))
                transmit(env, group)
                assertThat(env.guard.checkStatus(member.entityRef, TODAY))
                    .isEqualTo(GoLiveStatusDecision.Allowed(ExcludedBusinessStatus.OVERSEAS_S33_1))
                // the election is read on the transaction date: the day before it took effect the member is refused
                assertThat(env.guard.checkStatus(member.entityRef, TODAY.minusDays(1)))
                    .isEqualTo(GoLiveStatusDecision.Refused(ExcludedBusinessStatus.OVERSEAS_S33_1))
            }
        }
    }

    @Test
    fun `INV-AC-8586 go-live is refused for each excluded status, an excluded member goes live only on election`() {
        run { env ->
            env.withGroup(4) { _, group, members ->
                val statuses = listOf(Excl.OVERSEAS_S33_1, Excl.OVR, Excl.RC_ONLY, Excl.NOT_EXCLUDED)
                members.zip(statuses).forEach { (member, status) -> excluded(env, member, status) }
                val before = members.map { env.guard.checkStatus(it.entityRef, TODAY) }
                assertThat(before).containsExactly(
                    GoLiveStatusDecision.Refused(ExcludedBusinessStatus.OVERSEAS_S33_1),
                    GoLiveStatusDecision.Refused(ExcludedBusinessStatus.OVR),
                    GoLiveStatusDecision.Refused(ExcludedBusinessStatus.RC_ONLY),
                    GoLiveStatusDecision.Allowed(ExcludedBusinessStatus.NOT_EXCLUDED),
                )
                transmit(env, group)
                val after = members.map { env.guard.checkStatus(it.entityRef, TODAY) }
                assertThat(after.all { it is GoLiveStatusDecision.Allowed }).isTrue()
            }
        }
    }

    @Test
    fun `the election of VP-64 is set by tenant admin at group scope only`() {
        run { env ->
            env.withGroup(1) { _, group, members ->
                val entityAdmin = "entity.admin"
                val wrongRole =
                    assertThatThrownBy {
                        env.setPolicy(vp64, ScopeLevel.GROUP, group, null, "TRANSMIT_AND_GO_LIVE", role = entityAdmin)
                    }
                wrongRole.isInstanceOf(PolicyRefused::class.java)
                assertThat((wrongRole.actual() as PolicyRefused).refusal).isEqualTo(PolicyRefusal.ROLE_NOT_ALLOWED)
                val wrongScope =
                    assertThatThrownBy {
                        env.setPolicy(vp64, ScopeLevel.ENTITY, group, members.single(), "TRANSMIT_AND_GO_LIVE")
                    }
                assertThat((wrongScope.actual() as PolicyRefused).refusal).isEqualTo(PolicyRefusal.SCOPE_NOT_ALLOWED)
            }
        }
    }

    @Test
    fun `INV-AC-1260 VP-64 and VP-51 are set through the writer and read back, VP-28 offers only its default`() {
        run { env ->
            env.withGroup(1) { tenant, group, members ->
                val scope = PolicyScope.tenant(tenant, group = group.groupRef)

                fun selected(key: String) =
                    env.inTx {
                        env.reader
                            .resolve(VariationPointKey(key), scope, TODAY)
                            .selected
                            .single()
                            .key
                    }

                assertThat(selected(vp64)).isEqualTo("OMIT_EXCLUDED")
                transmit(env, group)
                assertThat(selected(vp64)).isEqualTo("TRANSMIT_AND_GO_LIVE")

                assertThat(selected("VP-51")).isEqualTo("MANDATORY_DATE")
                env.setPolicy(
                    "VP-51",
                    ScopeLevel.GROUP,
                    group,
                    null,
                    "IRAS_COMMUNICATED_DATE",
                    """{"date":"2028-10-01","iras_reference":"IRAS-REF-1"}""",
                    role = "entity.admin",
                )
                assertThat(selected("VP-51")).isEqualTo("IRAS_COMMUNICATED_DATE")

                assertThat(selected(vp28)).isEqualTo("PER_MEMBER")
                val planned = assertThatThrownBy { env.setPolicy(vp28, ScopeLevel.GROUP, group, null, "CONSOLIDATED") }
                planned.isInstanceOf(PolicyRefused::class.java)
                assertThat((planned.actual() as PolicyRefused).refusal).isEqualTo(PolicyRefusal.PLANNED)
                assertThat(selected(vp28)).isEqualTo("PER_MEMBER")
                assertThat(members).hasSize(1)
            }
        }
    }

    @Test
    fun `INV-AC-8589 INV-AC-8592 a group transmits per member by default and the Sender ID answer is provisional`() {
        run { env ->
            env.withGroup(2) { tenant, group, _ ->
                val scope = PolicyScope.tenant(tenant, group = group.groupRef)
                val mode =
                    env.inTx {
                        env.reader
                            .resolve(VariationPointKey(vp28), scope, TODAY)
                            .selected
                            .single()
                            .key
                    }
                assertThat(GroupTransmissionMode.valueOf(mode)).isEqualTo(GroupTransmissionMode.DEFAULT)
                assertThat(GroupTransmissionMode.DEFAULT).isEqualTo(GroupTransmissionMode.PER_MEMBER)
                val held = GroupTransmissionRules.SENDER_ID_OF_REPRESENTATIVE_MEMBER
                assertThat(held.question).isEqualTo("INV-Q-2839")
                assertThat(held.source).isEqualTo(AnswerSource.PROVISIONAL)
                assertThat(held.text)
                    .contains("representative member's Peppol ID")
                    .contains("next prescribed accounting period")
            }
        }
    }

    @Test
    fun `setPolicy is mounted by service-runtime for the variation points of this service`() {
        BoundaryFixture
            .runner()
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .run { context ->
                context.startupFailure?.let { throw it }
                val servlets =
                    context
                        .getBeansOfType(ServletRegistrationBean::class.java)
                        .values
                        .map { it.servlet }
                        .filterIsInstance<PolicyCommandServlet>()
                assertThat(servlets).hasSize(1)
                // no token: the route exists and answers 401 SRT-003, not 404
                val response = MockHttpServletResponse()
                servlets.single().service(MockHttpServletRequest("POST", "/internal/v1/policies/VP-64"), response)
                assertThat(response.status).isEqualTo(401)
                assertThat(response.contentAsString).contains("SRT-003")
            }
    }
}
