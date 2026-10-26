package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.contract.model.WithdrawConsentRequest
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
        var lastConsentRef = ""

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
                context
                    .getBean(ConsentController::class.java)
                    .grantConsent(
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
                    ).body!!
                    .consentRef
                    .also { lastConsentRef = it }
            }
            return client
        }

        fun withdraw(consentRef: String) =
            tenants.bind(tenant, signatory) {
                context
                    .getBean(ConsentController::class.java)
                    .withdrawConsent(consentRef, UUID.randomUUID(), WithdrawConsentRequest("Relationship ended"))
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

    private val filingKeys =
        listOf(
            "filing.confirm",
            "filed-return.upload",
            "reconciliation.run",
            "reconciliation.annotate",
            "reconciliation.attest",
        )

    private fun run(
        requirements: ScopeItemRequirements? = null,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture.runner(catalogueMatrix = true, requirements = requirements).run { context ->
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

    private val readKeys = listOf("submission.view-status", "doc.view.content", "period-readiness.view")
    private val readLookAlikes =
        listOf(
            "submission.view-statuses",
            "Submission.view-status",
            "doc.view.contents",
            "doc.view.content ",
            "period-readiness.views",
        )

    @Test
    fun `INV-Q-8864 A each read key is decided by the live consent for the operator`() =
        run { env ->
            readKeys.forEach { key ->
                env.assertDecision(Decision.ALLOW, agent, env.client(setOf(ScopeItem.SUBMIT_INVOICE_DATA)), key, "exact item")
                val other = setOf(ScopeItem.FILE_GST_RETURN, ScopeItem.MAPPING_APPROVAL)
                env.assertDecision(Decision.DENY, agent, env.client(other), key, "two other items")
                env.assertDecision(Decision.DENY, agent, env.client(emptySet()), key, "nothing held")
                env.assertDecision(Decision.DENY, agent, "CLI-NONE", key, "no consent")
                val ofOther = env.client(setOf(ScopeItem.SUBMIT_INVOICE_DATA))
                env.assertDecision(Decision.DENY, agent, "CLI-OTHER", key, "consent for another client entity")
                env.withdraw(env.lastConsentRef)
                env.assertDecision(Decision.DENY, agent, ofOther, key, "after withdrawal")
            }
        }

    @Test
    fun `INV-Q-8864 A look-alike read keys are refused with all three items granted`() =
        run { env ->
            val client = env.client(all)
            readLookAlikes.forEach {
                assertThat(runCatching { env.decide(agent, client, it) }.getOrNull())
                    .describedAs(it)
                    .isNotEqualTo(Decision.ALLOW)
            }
        }

    @Test
    fun `INV-Q-8864 A a file giving doc_view_content FILE_GST_RETURN refuses it under SUBMIT_INVOICE_DATA`() {
        val table = AgentScopeItemTable.parse(production().replace("doc.view.content\tSUBMIT_INVOICE_DATA", "doc.view.content\tFILE_GST_RETURN"))
        run(table) { env ->
            env.assertDecision(Decision.DENY, agent, env.client(setOf(ScopeItem.SUBMIT_INVOICE_DATA)), "doc.view.content", "wrong item")
        }
    }

    @Test
    fun `INV-Q-8864 A a row left out refuses that read key with SUBMIT_INVOICE_DATA granted`() {
        readKeys.forEach { gone ->
            val lines = production().split("\n").filterNot { it.startsWith("$gone\t") }
            run(AgentScopeItemTable.parse(lines.joinToString("\n"))) { env ->
                val client = env.client(all)
                env.assertDecision(Decision.DENY, agent, client, gone, "row left out")
                (readKeys - gone).forEach { env.assertDecision(Decision.ALLOW, agent, client, it, "row kept") }
            }
        }
    }

    private val fileOnly = setOf(ScopeItem.FILE_GST_RETURN)

    private fun Env.assertDecision(
        expected: Decision,
        who: String,
        client: String,
        key: String,
        why: String,
    ) = assertThat(decide(who, client, key)).describedAs("$key, $why").isEqualTo(expected)

    @Test
    fun `INV-Q-8866 A each filing key is allowed under exactly FILE_GST_RETURN for the operator`() =
        run { env ->
            filingKeys.forEach { key ->
                env.assertDecision(Decision.ALLOW, agent, env.client(fileOnly), key, "exact item")
                env.assertDecision(Decision.DENY, agent, env.client(all - fileOnly), key, "two other items")
                env.assertDecision(Decision.DENY, agent, env.client(emptySet()), key, "nothing held")
                env.assertDecision(Decision.DENY, agent, "CLI-NONE", key, "no consent")
                val withdrawn = env.client(fileOnly)
                env.withdraw(env.lastConsentRef)
                env.assertDecision(Decision.DENY, agent, withdrawn, key, "after withdrawal")
            }
        }

    @Test
    fun `INV-Q-8866 A the consent-derived approver is allowed filing_confirm under FILE_GST_RETURN`() =
        run { env ->
            val key = "filing.confirm"
            env.assertDecision(Decision.ALLOW, approver, env.client(fileOnly), key, "exact item")
            env.assertDecision(Decision.DENY, approver, env.client(all - fileOnly), key, "two other items")
            env.assertDecision(Decision.DENY, approver, env.client(emptySet()), key, "nothing held")
            env.assertDecision(Decision.DENY, approver, "CLI-NONE", key, "no consent")
            val withdrawn = env.client(fileOnly)
            env.withdraw(env.lastConsentRef)
            env.assertDecision(Decision.DENY, approver, withdrawn, key, "after withdrawal")
        }

    @Test
    fun `INV-Q-8866 A look-alike filing keys are refused with all three items granted`() =
        run { env ->
            val client = env.client(all)
            listOf("filing.confirms", "Filing.confirm", "filing.confirm ", "filed-return.uploads").forEach {
                assertThat(runCatching { env.decide(agent, client, it) }.getOrNull())
                    .describedAs(it)
                    .isNotEqualTo(Decision.ALLOW)
            }
        }

    @Test
    fun `INV-Q-8866 A a file giving reconciliation_run the item SUBMIT_INVOICE_DATA refuses it`() {
        val from = "reconciliation.run\tFILE_GST_RETURN"
        val table = AgentScopeItemTable.parse(production().replace(from, "reconciliation.run\tSUBMIT_INVOICE_DATA"))
        run(table) { env ->
            env.assertDecision(Decision.DENY, agent, env.client(fileOnly), "reconciliation.run", "wrong item in file")
        }
    }

    @Test
    fun `INV-Q-8866 A a row left out refuses that key for the agent with FILE_GST_RETURN granted`() {
        val lines = production().split("\n").filterNot { it.startsWith("reconciliation.attest\t") }
        run(AgentScopeItemTable.parse(lines.joinToString("\n"))) { env ->
            val client = env.client(all)
            env.assertDecision(Decision.DENY, agent, client, "reconciliation.attest", "row left out")
            env.assertDecision(Decision.ALLOW, agent, client, "reconciliation.run", "row kept")
        }
    }

    private fun production(): String =
        checkNotNull(javaClass.getResourceAsStream("/agent-scope-items.tsv")).bufferedReader().use { it.readText() }
}
