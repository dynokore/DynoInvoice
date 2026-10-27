package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.IdpGroupController
import com.dynokore.gst.access.adapters.http.PrincipalsController
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.IdpGroupMappingRequest
import com.dynokore.gst.access.contract.model.WithdrawConsentRequest
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.LocalDate
import java.util.UUID

/**
 * INV-T-9379 (INV-Q-8868 A): `grantRole` takes the scope `consent:<tenantRef>`, and the decision of `authorise` runs on
 * the scope the repository reloads with the live consent check. Real beans over a real PostgreSQL; the live consent is
 * read in the call.
 */
class ConsentDerivedGrantTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val admin = "user:tenant-admin"
    private val approver = "user:approver"
    private val signatory = "user:signatory"
    private val scopeText = "consent:$tenant"
    private val all = ScopeItem.entries.toSet()

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        private var next = 0

        init {
            Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
        }

        fun grantRole(scope: String = scopeText) =
            tenants.bind(tenant, admin) {
                context
                    .getBean(GrantsController::class.java)
                    .grantRole(
                        UUID.randomUUID(),
                        GrantRequest(
                            GrantRequest.GranteeKind.STAFF,
                            approver,
                            "tax.approver",
                            scope,
                            LocalDate.now().minusDays(1),
                            LocalDate.now().plusDays(30),
                        ),
                    )
            }

        fun rows(): List<com.dynokore.gst.access.domain.grant.Grant> =
            tenants.bind(tenant, admin) {
                context.getBean(TransactionRunner::class.java).inTransaction {
                    context.getBean(GrantRepository::class.java).findByIdentity(IdentityRef(approver))
                }
            }

        /** A fresh client entity, with a live consent of [agent] holding [items] when [items] is not null. */
        fun client(
            items: Set<ScopeItem>?,
            agent: String = "user:agent",
        ): String {
            val client = "CLI-${next++}"
            context
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(tenant, client, StructureChange.CREATED, BoundaryFixture.entityNode()),
                )
            items ?: return client
            Seeds.grant(context, tenant, SeedSpec(signatory, Role.CLIENT_SIGNATORY, Scope.Entity(EntityRef(client))))
            return tenants.bind(tenant, signatory) {
                context
                    .getBean(ConsentController::class.java)
                    .grantConsent(
                        UUID.randomUUID(),
                        GrantConsentRequest(
                            clientRef = client,
                            agentRef = agent,
                            validFrom = LocalDate.of(2026, 1, 1),
                            authorisingPersonEmail = "director@client.example",
                            submitInvoiceData = ScopeItem.SUBMIT_INVOICE_DATA in items,
                            fileGstReturn = ScopeItem.FILE_GST_RETURN in items,
                            mappingApproval = ScopeItem.MAPPING_APPROVAL in items,
                        ),
                    ).body!!
                    .consentRef
                    .also { lastConsent = it }
                    .let { client }
            }
        }

        var lastConsent = ""

        fun withdraw() =
            tenants.bind(tenant, signatory) {
                context
                    .getBean(ConsentController::class.java)
                    .withdrawConsent(lastConsent, UUID.randomUUID(), WithdrawConsentRequest("Relationship ended"))
            }

        fun decide(client: String): Decision =
            tenants.bind(tenant, signatory) {
                context
                    .getBean(AuthoriseController::class.java)
                    .authorise(approver, "mapping-pack.approve", "entity:$client")
                    .body!!
                    .decision
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true, requirements = null).run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    @Test
    fun `INV-AC-7741 grantRole stores a consent-level grant and a repeat stores no second row`() =
        run { env ->
            val first = env.grantRole()
            assertThat(first.statusCode.value()).isEqualTo(201)
            assertThat(first.body!!.scope).isEqualTo(scopeText)
            val rows = env.rows()
            assertThat(rows).hasSize(1)
            assertThat(rows[0].scope.level).isEqualTo(ScopeLevel.CONSENT)
            assertThat((rows[0].scope as Scope.ConsentDerived).tenant.value).isEqualTo(tenant.toString())
            val repeat = env.grantRole()
            assertThat(repeat.statusCode.value()).isEqualTo(200)
            assertThat(repeat.body!!.grantRef).isEqualTo(first.body!!.grantRef)
            assertThat(repeat.body!!.scope).isEqualTo(scopeText)
            assertThat(env.rows()).hasSize(1)
        }

    @Test
    fun `INV-AC-7741 the decision follows the live consent read in the call`() =
        run { env ->
            env.grantRole()
            val live = env.client(setOf(ScopeItem.MAPPING_APPROVAL))
            assertThat(env.decide(live)).describedAs("live consent holding MAPPING_APPROVAL").isEqualTo(Decision.ALLOW)
            val wrongItems = env.client(setOf(ScopeItem.SUBMIT_INVOICE_DATA, ScopeItem.FILE_GST_RETURN))
            assertThat(env.decide(wrongItems)).describedAs("no MAPPING_APPROVAL").isEqualTo(Decision.DENY)
            val noConsent = env.client(null)
            assertThat(env.decide(noConsent)).describedAs("no consent").isEqualTo(Decision.DENY)
            val withdrawn = env.client(all)
            assertThat(env.decide(withdrawn)).isEqualTo(Decision.ALLOW)
            env.withdraw()
            assertThat(env.decide(withdrawn)).describedAs("after withdrawConsent").isEqualTo(Decision.DENY)
            assertThat(env.decide(live)).describedAs("the other client is unaffected").isEqualTo(Decision.ALLOW)
        }

    @Test
    fun `INV-AC-7741 the other callers refuse the consent scope like any unsupported scope and store nothing`() =
        run { env ->
            val idp = env.context.getBean(IdpGroupController::class.java)
            val principals = env.context.getBean(PrincipalsController::class.java)
            val refusals =
                listOf(
                    {
                        idp.mapIdpGroup(
                            UUID.randomUUID(),
                            IdpGroupMappingRequest("g", "tax.approver", scopeText, null, null),
                        )
                    },
                    {
                        principals.createPrincipal(
                            UUID.randomUUID(),
                            CreatePrincipalRequest(CreatePrincipalRequest.PrincipalType.INGEST, scopeText, null),
                        )
                    },
                )
            refusals.forEach { call ->
                val thrown = env.tenants.bind(tenant, admin) { runCatching { call() }.exceptionOrNull() }
                assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("SRT-001")
                    .hasMessageContaining("scope is not supported")
            }
            assertThat(env.rows()).isEmpty()
        }
}
