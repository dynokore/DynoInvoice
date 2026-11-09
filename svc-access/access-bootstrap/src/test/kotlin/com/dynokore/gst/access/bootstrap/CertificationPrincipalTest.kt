package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.GatewayCaller
import com.dynokore.gst.access.adapters.http.GatewayTokenIssuance
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.http.PrincipalOperations
import com.dynokore.gst.access.adapters.http.PrincipalsController
import com.dynokore.gst.access.application.token.TenantEnvironment
import com.dynokore.gst.access.application.token.TenantEnvironments
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.PrincipalResponse
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureEnvironment
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-11814 (INV-T-11816, INV-Q-11811 A, INV-Q-9032 A), INV-REQ-584: createPrincipal refuses a `certification`
 * principal for a tenant whose environment is not SANDBOX (403 SRT-004) and stores neither a principal nor a
 * credential row. The real context over a real PostgreSQL; the replica is fed with `OrganisationStructureChanged`.
 */
class CertificationPrincipalTest {
    private val versions = AtomicLong()
    private val certification = CreatePrincipalRequest.PrincipalType.CERTIFICATION

    private fun run(block: (PrincipalKit) -> Unit) = PrincipalKit.run(block)

    private fun PrincipalKit.node(
        tenant: TenantId,
        kind: StructureNodeKind,
        environment: StructureEnvironment?,
    ) = context.getBean(StructureReplica::class.java).accept(
        OrganisationStructureChanged(
            tenant,
            "TEN-" + tenant.toString().take(8),
            StructureChange.CREATED,
            StructureNode(kind, null, false, "node", null, emptyList(), versions.incrementAndGet(), environment),
        ),
    )

    private fun PrincipalKit.tenantNode(environment: StructureEnvironment?) =
        node(tenant, StructureNodeKind.TENANT, environment)

    private fun PrincipalKit.call(
        type: CreatePrincipalRequest.PrincipalType,
        scope: String = "tenant:$tenant",
        controller: PrincipalsController = principals,
    ): ResponseEntity<PrincipalResponse> {
        val jwk = jwkOf(newKey("client-key-" + UUID.randomUUID()))
        return asAdmin {
            controller.createPrincipal(
                UUID.randomUUID(),
                CreatePrincipalRequest(type, scope, null, PrincipalKit.JWT, jwk, null),
            )
        }
    }

    private fun PrincipalKit.assertRefusedWithNoRows(block: () -> Any?) {
        val principalRows = principalCount()
        val secretRows = secretRowCount()
        val thrown = runCatching { block() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        assertThat((thrown as ApiRefusal).code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(principalCount()).isEqualTo(principalRows)
        assertThat(secretRowCount()).isEqualTo(secretRows)
    }

    @Test
    fun `INV-AC-11814 a certification principal of a SANDBOX tenant is created with 201`() =
        run { kit ->
            kit.tenantNode(StructureEnvironment.SANDBOX)
            val principalRows = kit.principalCount()
            val secretRows = kit.secretRowCount()
            val created = kit.call(certification)
            assertThat(created.statusCode.value()).isEqualTo(201)
            assertThat(kit.principalCount()).isEqualTo(principalRows + 1)
            assertThat(kit.secretRowCount()).isEqualTo(secretRows + 1)
        }

    @Test
    fun `INV-AC-11814 a certification principal of a PRODUCTION tenant is refused 403 and stores no row`() =
        run { kit ->
            kit.tenantNode(StructureEnvironment.PRODUCTION)
            kit.assertRefusedWithNoRows { kit.call(certification) }
        }

    @Test
    fun `INV-AC-11814 a certification principal of a tenant with no environment is refused 403 and stores no row`() =
        run { kit ->
            kit.tenantNode(null)
            kit.assertRefusedWithNoRows { kit.call(certification) }
            kit.tenantNode(StructureEnvironment.UNKNOWN)
            kit.assertRefusedWithNoRows { kit.call(certification) }
        }

    @Test
    fun `INV-AC-11814 a certification principal of a tenant with no node is refused 403 and stores no row`() =
        run { kit ->
            kit.assertRefusedWithNoRows { kit.call(certification) }
        }

    @Test
    fun `INV-AC-11814 an ingest principal of a PRODUCTION tenant is created with 201`() =
        run { kit ->
            kit.tenantNode(StructureEnvironment.PRODUCTION)
            kit.entity("ENT-1")
            val created = kit.call(CreatePrincipalRequest.PrincipalType.INGEST, scope = "entity:ENT-1")
            assertThat(created.statusCode.value()).isEqualTo(201)
            assertThat(kit.principalCount()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-11814 probe the decision follows the own tenant of the call next to a SANDBOX tenant`() =
        run { kit ->
            kit.node(TenantId(UUID.randomUUID()), StructureNodeKind.TENANT, StructureEnvironment.SANDBOX)
            kit.tenantNode(StructureEnvironment.PRODUCTION)
            kit.assertRefusedWithNoRows { kit.call(certification) }
        }

    @Test
    fun `INV-AC-11814 probe a node of kind ENTITY that carries SANDBOX is ignored and the call is refused 403`() =
        run { kit ->
            kit.node(kit.tenant, StructureNodeKind.ENTITY, StructureEnvironment.SANDBOX)
            kit.assertRefusedWithNoRows { kit.call(certification) }
        }

    @Test
    fun `INV-AC-11814 probe a caller who may not manage grants is refused by requireManager with no lookup`() =
        run { kit ->
            var lookups = 0
            val reader =
                TenantEnvironments { _: TenantRef ->
                    lookups++
                    TenantEnvironment.SANDBOX
                }
            val controller =
                PrincipalsController(
                    kit.context.getBean(PrincipalOperations::class.java),
                    kit.context.getBean(GatewayTokenIssuance::class.java),
                    kit.tenants,
                    GrantManagerCheck.DENY_ALL,
                    GatewayCaller { true },
                    JsonMapper.builder().build(),
                    reader,
                )
            kit.assertRefusedWithNoRows { kit.call(certification, controller = controller) }
            assertThat(lookups).isZero()
        }
}
