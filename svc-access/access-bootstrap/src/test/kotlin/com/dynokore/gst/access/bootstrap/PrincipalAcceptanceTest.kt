package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.PrincipalsController
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.PrincipalResponse
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * createPrincipal and revokePrincipal at the service boundary (INV-T-4937): the real context over a real PostgreSQL
 * with forced row-level security, the controller called under a bound tenant. Test names carry the criterion key
 * (acKeyCoverage). Only the part of each criterion that this service produces and the contract publishes is asserted
 * (see the merge request, "Gaps").
 */
class PrincipalAcceptanceTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val admin = "user:tenant-admin"
    private val sixTypes =
        listOf("INGEST", "FILING_WORKFLOW", "AP_CALLBACK", "REPORTING", "MONITORING", "CERTIFICATION")
    private val legacy = listOf("MACHINE_CLIENT", "SCHEDULED_JOB", "AGENT", "SUPPORT_TOOL", "INTEGRATION")

    private fun run(block: (AssertableWebApplicationContext, PrincipalsController) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
            block(context, context.getBean(PrincipalsController::class.java))
        }
    }

    private fun AssertableWebApplicationContext.inTenant(block: () -> Any?) =
        getBean(TenantContext::class.java).bind(tenant, admin) { block() }

    private fun AssertableWebApplicationContext.count(): Int =
        getBean(TenantContext::class.java).bind(tenant, admin) {
            getBean(TransactionRunner::class.java).inTransaction {
                getBean(DSLContext::class.java).fetchCount(DSL.table(DSL.name("access", "service_principal")))
            }
        }

    // a MONITORING principal's scope is the installation (INV-Q-8779 A)
    private fun request(
        type: CreatePrincipalRequest.PrincipalType,
        callingSystem: String? = null,
    ) = CreatePrincipalRequest(
        type,
        if (type == CreatePrincipalRequest.PrincipalType.MONITORING) "installation" else "tenant:$tenant",
        callingSystem,
    )

    private fun create(
        context: AssertableWebApplicationContext,
        controller: PrincipalsController,
        type: CreatePrincipalRequest.PrincipalType,
    ): PrincipalResponse =
        context.inTenant {
            controller
                .createPrincipal(
                    UUID.randomUUID(),
                    request(
                        type,
                        if (type ==
                            CreatePrincipalRequest.PrincipalType.FILING_WORKFLOW
                        ) {
                            "orchestrator"
                        } else {
                            null
                        },
                    ),
                ).body
        } as PrincipalResponse

    @Test
    fun `INV-AC-8614 six types are created with exactly one type each and the five legacy members are refused`() =
        run { context, controller ->
            sixTypes.forEach { wire ->
                val created = create(context, controller, CreatePrincipalRequest.PrincipalType.valueOf(wire))
                assertThat(created.principalType).isEqualTo(wire)
                assertThat(created.status).isEqualTo(PrincipalResponse.Status.ACTIVE)
            }
            assertThat(context.count()).isEqualTo(6)
            legacy.forEach { wire ->
                val refused =
                    runCatching {
                        context.inTenant {
                            controller.createPrincipal(
                                UUID.randomUUID(),
                                request(CreatePrincipalRequest.PrincipalType.valueOf(wire)),
                            )
                        }
                    }.exceptionOrNull()
                assertThat(refused).describedAs(wire).isNotNull().hasMessageContaining("SRT-001")
            }
            assertThat(context.count()).isEqualTo(6)
        }

    @Test
    fun `INV-AC-8614 and INV-AC-1697 a request for two types is refused and creates nothing`() =
        run { context, controller ->
            val body = """{"principalType":"REPORTING,FILING_WORKFLOW","scope":"tenant:$tenant","callingSystem":"x"}"""
            val read = runCatching { JsonMapper.builder().build().readValue(body, CreatePrincipalRequest::class.java) }
            assertThat(read.isFailure).describedAs("the contract has no way to carry two types").isTrue()
            assertThat(context.count()).isZero()
            // a FILING_WORKFLOW request without callingSystem is refused too and creates nothing (INV-REQ-587)
            val refused =
                runCatching {
                    context.inTenant {
                        controller.createPrincipal(
                            UUID.randomUUID(),
                            request(CreatePrincipalRequest.PrincipalType.FILING_WORKFLOW),
                        )
                    }
                }.exceptionOrNull()
            assertThat(refused).isNotNull().hasMessageContaining("SRT-001")
            assertThat(context.count()).isZero()
        }

    @Test
    fun `INV-AC-8616 revokePrincipal revokes and emits one AuthorityChanged REVOCATION`() =
        run { context, controller ->
            context
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(
                        tenant,
                        "ENT-1",
                        StructureChange.CREATED,
                        BoundaryFixture.entityNode(),
                    ),
                )
            val created =
                context.inTenant {
                    controller
                        .createPrincipal(
                            UUID.randomUUID(),
                            CreatePrincipalRequest(CreatePrincipalRequest.PrincipalType.INGEST, "entity:ENT-1"),
                        ).body
                } as PrincipalResponse
            val captured = context.getBean(CapturedEvents::class.java)
            captured.envelopes.clear()
            val revoked = context.inTenant { controller.revokePrincipal(created.principalRef, UUID.randomUUID()).body }
            assertThat((revoked as PrincipalResponse).status).isEqualTo(PrincipalResponse.Status.REVOKED)
            val events = captured.envelopes.filter { it.type == ProductEventTypes.AUTHORITY_CHANGED }
            assertThat(events).hasSize(1)
            val payload = JsonMapper.builder().build().readTree(events.single().dataJson)
            assertThat(payload.get("changeKind").asString()).isEqualTo("REVOCATION")
            assertThat(payload.get("principalRef").asString()).isEqualTo(created.principalRef)
            assertThat(payload.get("principalRecord").get("state").asString()).isEqualTo("REVOKED")
            // a repeat is a no-op: no second event
            captured.envelopes.clear()
            context.inTenant { controller.revokePrincipal(created.principalRef, UUID.randomUUID()) }
            assertThat(captured.envelopes.filter { it.type == ProductEventTypes.AUTHORITY_CHANGED }).isEmpty()
        }
}
