package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.LocalDate
import java.util.UUID

/**
 * INV-Q-8874 A at the application context with the real `ScopeItemRequirements` bean (built from
 * `agent-scope-items.tsv`, not a test table): the decision of `authorise` for an `agent.operator` grant or a
 * consent-derived grant, read with the live consent record of the call.
 */
class AgentScopeItemRequirementsTest {
    private val signatory = "user:signatory"
    private val agent = "user:agent"
    private val approver = "user:approver"
    private val all = ScopeItem.entries.toSet()

    private val operatorRows =
        mapOf(
            "masterdata-exception.resolve" to ScopeItem.SUBMIT_INVOICE_DATA,
            "period-readiness.run" to ScopeItem.SUBMIT_INVOICE_DATA,
            "submission.replay" to ScopeItem.SUBMIT_INVOICE_DATA,
            "mapping-pack.author" to ScopeItem.SUBMIT_INVOICE_DATA,
            "transaction.ingest" to ScopeItem.SUBMIT_INVOICE_DATA,
        )

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        private val tenants = context.getBean(TenantContext::class.java)
        private var next = 0

        /**
         * A fresh client entity with a live consent of [consentAgent] holding [items], the operator's and the
         * approver's grants on it.
         */
        fun client(
            items: Set<ScopeItem>,
            consentAgent: String = agent,
        ): String {
            val client = "CLI-${next++}"
            context
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(tenant, client, StructureChange.CREATED, BoundaryFixture.entityNode()),
                )
            Seeds.grant(context, tenant, SeedSpec(signatory, Role.CLIENT_SIGNATORY, Scope.Entity(EntityRef(client))))
            Seeds.grant(context, tenant, SeedSpec(agent, Role.AGENT_OPERATOR, Scope.Entity(EntityRef(client))))
            val check = context.getBean(ConsentCheck::class.java)
            Seeds.grant(
                context,
                tenant,
                SeedSpec(approver, Role.TAX_APPROVER, Scope.ConsentDerived(TenantRef(tenant.toString()), check)),
            )
            tenants.bind(tenant, signatory) {
                context.getBean(ConsentController::class.java).grantConsent(
                    UUID.randomUUID(),
                    GrantConsentRequest(
                        clientRef = client,
                        agentRef = consentAgent,
                        validFrom = LocalDate.of(2026, 1, 1),
                        authorisingPersonEmail = "director@client.example",
                        submitInvoiceData = ScopeItem.SUBMIT_INVOICE_DATA in items,
                        fileGstReturn = ScopeItem.FILE_GST_RETURN in items,
                        mappingApproval = ScopeItem.MAPPING_APPROVAL in items,
                    ),
                )
            }
            return client
        }

        fun decide(
            who: String,
            client: String,
            permission: String,
        ): Decision =
            tenants.bind(tenant, signatory) {
                context
                    .getBean(
                        AuthoriseController::class.java,
                    ).authorise(who, permission, "entity:$client")
                    .body!!
                    .decision
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true, requirements = null).run { context ->
            context.startupFailure?.let { throw it }
            assertThat(context.getBean(ScopeItemRequirements::class.java)).isNotNull()
            block(Env(context))
        }
    }

    @Test
    fun `INV-Q-8874 probe 1 each row is refused under the two other items and allowed under exactly its item`() =
        run { env ->
            operatorRows.forEach { (permission, item) ->
                val others = env.client(all - item)
                val exact = env.client(setOf(item))
                assertThat(
                    env.decide(agent, others, permission),
                ).describedAs("$permission without $item").isEqualTo(Decision.DENY)
                assertThat(
                    env.decide(agent, exact, permission),
                ).describedAs("$permission with $item").isEqualTo(Decision.ALLOW)
            }
            val approve = "mapping-pack.approve"
            val others = env.client(all - ScopeItem.MAPPING_APPROVAL)
            val exact = env.client(setOf(ScopeItem.MAPPING_APPROVAL))
            assertThat(env.decide(approver, others, approve)).isEqualTo(Decision.DENY)
            assertThat(env.decide(approver, exact, approve)).isEqualTo(Decision.ALLOW)
        }

    @Test
    fun `INV-Q-8874 probe 2 a permission outside the table is refused with all three items granted`() =
        run { env ->
            val client = env.client(all)
            val approverClient = env.client(all)
            listOf(
                "submission.view-status",
                "doc.view.content",
                "period-readiness.view",
                "validation.override",
                "audit-pack.export",
            ).forEach { assertThat(env.decide(agent, client, it)).describedAs(it).isEqualTo(Decision.DENY) }
            assertThat(env.decide(approver, approverClient, "mapping-pack.activate")).isEqualTo(Decision.DENY)
        }

    @Test
    fun `INV-Q-8874 probe 3 look-alike keys are refused`() =
        run { env ->
            val client = env.client(all)
            listOf("submission.replays", "Submission.replay", "submission.replay ").forEach {
                assertThat(
                    runCatching { env.decide(agent, client, it) }.getOrNull(),
                ).describedAs(it).isNotEqualTo(Decision.ALLOW)
            }
        }

    @Test
    fun `INV-Q-8874 probe 5 the real bean allows submission_replay under SUBMIT_INVOICE_DATA only`() =
        run { env ->
            assertThat(
                env.decide(agent, env.client(setOf(ScopeItem.SUBMIT_INVOICE_DATA)), "submission.replay"),
            ).isEqualTo(Decision.ALLOW)
            assertThat(
                env.decide(agent, env.client(setOf(ScopeItem.FILE_GST_RETURN)), "submission.replay"),
            ).isEqualTo(Decision.DENY)
        }

    @Test
    fun `INV-Q-8874 probe 6 a consent-derived approver with a live consent holding no item is refused`() =
        run { env ->
            assertThat(
                env.decide(approver, env.client(emptySet()), "mapping-pack.approve"),
            ).isEqualTo(Decision.DENY)
        }

    @Test
    fun `INV-Q-9320 the approver is decided by the live consent and item, the operator also by the consent agent`() =
        run { env ->
            val ofOther = env.client(all, consentAgent = "user:other-agent")
            assertThat(env.decide(approver, ofOther, "mapping-pack.approve")).isEqualTo(Decision.ALLOW)
            assertThat(env.decide(agent, ofOther, "submission.replay")).isEqualTo(Decision.DENY)
            val withoutConsent = "CLI-NONE"
            assertThat(env.decide(approver, withoutConsent, "mapping-pack.approve")).isEqualTo(Decision.DENY)
        }
}
