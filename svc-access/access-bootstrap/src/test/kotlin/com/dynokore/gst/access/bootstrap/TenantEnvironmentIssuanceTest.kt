package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.structure.ReplicaTenantEnvironments
import com.dynokore.gst.access.application.token.TenantEnvironment
import com.dynokore.gst.access.application.token.TenantEnvironments
import com.dynokore.gst.access.contract.model.IssueAccessTokenResponse.Decision
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureEnvironment
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-9149 at the service boundary (INV-T-9157, INV-Q-9032 A): the environment claim of the access token and the
 * refusal of a `certification` token outside a SANDBOX tenant, read from the TENANT node of the structure replica.
 * The real context over a real PostgreSQL; the replica is fed with `OrganisationStructureChanged` events.
 */
class TenantEnvironmentIssuanceTest {
    private val mapper = JsonMapper.builder().build()

    private fun run(block: (TokenKit) -> Unit) {
        BoundaryFixture.deleteVerificationKeys()
        val key = TestSecrets.newSigningKey()
        val mounted = BoundaryFixture.mount(key)
        BoundaryFixture.runner(mounted = mounted).run { context ->
            context.startupFailure?.let { throw it }
            val tokens = TokenKit(PrincipalKit(context), mounted, context.getBean(SignerCalls::class.java))
            try {
                block(tokens)
            } finally {
                tokens.close()
            }
        }
    }

    private class Subject(
        val ref: String,
        val proof: String,
    )

    private fun TokenKit.principal(
        type: String,
        scope: String,
    ): Subject {
        val key = kit.newKey("client-key-" + UUID.randomUUID())
        val created = kit.create(type, scope = scope, method = PrincipalKit.JWT, key = key)
        return Subject(created.principalRef, kit.proof(key, created.principalRef))
    }

    private fun TokenKit.fresh(subject: Subject) = issue(subject.ref, subject.proof)

    private val versions = AtomicLong()

    private fun TokenKit.node(
        tenant: TenantId,
        nodeRef: String,
        kind: StructureNodeKind,
        environment: StructureEnvironment?,
        retired: Boolean = false,
    ) = kit.context
        .getBean(StructureReplica::class.java)
        .accept(
            OrganisationStructureChanged(
                tenant,
                nodeRef,
                if (retired) StructureChange.RETIRED else StructureChange.CREATED,
                StructureNode(kind, null, retired, "node", null, emptyList(), versions.incrementAndGet(), environment),
            ),
        )

    private fun TokenKit.tenantNode(
        environment: StructureEnvironment?,
        ref: String = "TEN-1",
        tenant: TenantId = kit.tenant,
    ) = node(tenant, ref, StructureNodeKind.TENANT, environment)

    private fun TokenKit.reader(): TenantEnvironments = kit.context.getBean(TenantEnvironments::class.java)

    private fun TokenKit.environmentOf(tenant: TenantId = kit.tenant) = reader().of(TenantRef(tenant.toString()))

    private fun claimsOf(token: String): Map<String, Any?> = SignedJWT.parse(token).jwtClaimsSet.claims

    private fun TokenKit.certification() = principal("CERTIFICATION", "tenant:${kit.tenant}")

    private fun TokenKit.assertRefusedUnknown(subject: Subject) {
        val before = calls.signs.get()
        val refused = fresh(subject)
        assertThat(refused.decision).isEqualTo(Decision.REFUSED)
        assertThat(refused.reasonCode).isEqualTo("UNKNOWN")
        assertThat(refused.accessToken).isNull()
        assertThat(refused.keyId).isNull()
        assertThat(calls.signs.get()).isEqualTo(before)
        val body = mapper.writeValueAsString(refused)
        assertThat(body).doesNotContain("SANDBOX").doesNotContain("PRODUCTION").doesNotContain("sandbox")
    }

    @Test
    fun `INV-AC-9149 a certification principal of a SANDBOX tenant is ISSUED with the claim environment SANDBOX`() =
        run { t ->
            t.kit.clock.freeze()
            t.tenantNode(StructureEnvironment.SANDBOX)
            val issued = t.fresh(t.certification())
            assertThat(issued.decision).isEqualTo(Decision.ISSUED)
            val claims = claimsOf(issued.accessToken!!)
            assertThat(claims["environment"]).isEqualTo("SANDBOX")
            assertThat(claims["scope"]).isEqualTo("certification")
            assertThat(claims["tenant_id"]).isEqualTo(t.kit.tenant.toString())
        }

    @Test
    fun `INV-AC-9149 a certification principal is REFUSED with UNKNOWN unless SANDBOX`() =
        run { t ->
            t.kit.clock.freeze()
            val certification = t.certification()
            // no TENANT node in the replica at all
            t.assertRefusedUnknown(certification)
            listOf(StructureEnvironment.PRODUCTION, null, StructureEnvironment.UNKNOWN).forEach { env ->
                t.tenantNode(env)
                t.assertRefusedUnknown(certification)
            }
        }

    @Test
    fun `INV-AC-9149 probe a SANDBOX tenant whose node wrote sandbox or STAGING on the wire is REFUSED`() =
        run { t ->
            t.kit.clock.freeze()
            val certification = t.certification()
            listOf("sandbox", "STAGING", "Sandbox", "SANDBOX ").forEach { wire ->
                t.tenantNode(StructureEnvironment.of(wire))
                assertThat(StructureEnvironment.of(wire)).isEqualTo(StructureEnvironment.UNKNOWN)
                t.assertRefusedUnknown(certification)
            }
        }

    @Test
    fun `INV-AC-9149 probe the decision follows the own tenant of the token next to a SANDBOX tenant`() =
        run { t ->
            t.kit.clock.freeze()
            val other = TenantId(UUID.randomUUID())
            t.tenantNode(StructureEnvironment.SANDBOX, "TEN-OTHER", other)
            t.tenantNode(StructureEnvironment.PRODUCTION, "TEN-1")
            t.assertRefusedUnknown(t.certification())
            assertThat(t.environmentOf()).isEqualTo(TenantEnvironment.PRODUCTION)
            assertThat(t.environmentOf(other)).isEqualTo(TenantEnvironment.SANDBOX)
        }

    @Test
    fun `INV-AC-9149 probe a node of kind ENTITY that carries SANDBOX is ignored`() =
        run { t ->
            t.kit.clock.freeze()
            t.node(t.kit.tenant, t.kit.tenant.toString(), StructureNodeKind.ENTITY, StructureEnvironment.SANDBOX)
            assertThat(t.environmentOf()).isNull()
            t.assertRefusedUnknown(t.certification())
        }

    @Test
    fun `INV-AC-9149 principals of the other scopes are unaffected and the claim follows the node`() =
        run { t ->
            t.kit.clock.freeze()
            t.kit.entity("ENT-1")
            val ingest = t.principal("INGEST", "entity:ENT-1")
            val monitoring = t.principal("MONITORING", "installation")
            t.tenantNode(StructureEnvironment.PRODUCTION)
            val production = t.fresh(ingest)
            assertThat(production.decision).isEqualTo(Decision.ISSUED)
            assertThat(claimsOf(production.accessToken!!)["environment"]).isEqualTo("PRODUCTION")
            // MONITORING has no tenant: the claim is absent although a node exists
            val monitored = claimsOf(t.fresh(monitoring).accessToken!!)
            assertThat(monitored).doesNotContainKey("tenant_id").doesNotContainKey("environment")
            t.tenantNode(StructureEnvironment.SANDBOX)
            assertThat(claimsOf(t.fresh(ingest).accessToken!!)["environment"]).isEqualTo("SANDBOX")
            listOf(null, StructureEnvironment.UNKNOWN).forEach { env ->
                t.tenantNode(env)
                val issued = t.fresh(ingest)
                assertThat(issued.decision).isEqualTo(Decision.ISSUED)
                assertThat(claimsOf(issued.accessToken!!)).doesNotContainKey("environment")
            }
        }

    @Test
    fun `INV-AC-9149 reader finds the TENANT node whose nodeRef is in the TEN form and none for no node`() =
        run { t ->
            assertThat(t.environmentOf()).isNull()
            t.tenantNode(StructureEnvironment.SANDBOX, "TEN-000123")
            assertThat(t.environmentOf()).isEqualTo(TenantEnvironment.SANDBOX)
            assertThat(t.reader()).isInstanceOf(ReplicaTenantEnvironments::class.java)
        }

    @Test
    fun `INV-AC-9149 reader reads null for a retired sole node, another tenant node only, and a non UUID tenant`() =
        run { t ->
            t.node(t.kit.tenant, "TEN-1", StructureNodeKind.TENANT, StructureEnvironment.SANDBOX, retired = true)
            assertThat(t.environmentOf()).isNull()
            val other = TenantId(UUID.randomUUID())
            t.tenantNode(StructureEnvironment.SANDBOX, "TEN-2", other)
            assertThat(t.environmentOf()).isNull()
            assertThat(t.reader().of(TenantRef("TEN-2"))).isNull()
            assertThat(t.reader().of(TenantRef("not-a-uuid"))).isNull()
        }

    @Test
    fun `INV-AC-9149 two TENANT nodes of one tenant throw whatever their values and no token is signed`() =
        run { t ->
            t.kit.clock.freeze()
            t.kit.entity("ENT-1")
            val certification = t.certification()
            val ingest = t.principal("INGEST", "entity:ENT-1")
            val monitoring = t.principal("MONITORING", "installation")
            val pairs =
                listOf(
                    StructureEnvironment.SANDBOX to StructureEnvironment.PRODUCTION,
                    StructureEnvironment.SANDBOX to StructureEnvironment.SANDBOX,
                )
            pairs.forEach { (first, second) ->
                t.tenantNode(first, "TEN-A")
                t.tenantNode(second, "TEN-B")
                assertThatThrownBy { t.environmentOf() }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("2")
                    .message()
                    .doesNotContain("SANDBOX")
                    .doesNotContain("PRODUCTION")
                val before = t.calls.signs.get()
                listOf(certification, ingest).forEach { subject ->
                    assertThatThrownBy { t.fresh(subject) }.isInstanceOf(IllegalStateException::class.java)
                }
                assertThat(t.calls.signs.get()).isEqualTo(before)
            }
            // MONITORING has a tenant record too, so the reader runs for it as well
            assertThatThrownBy { t.fresh(monitoring) }.isInstanceOf(IllegalStateException::class.java)
        }

    @Test
    fun `INV-AC-9149 probe StructureNode carries the environment so a BOM below 10 33 0 fails to build`() {
        val node =
            StructureNode(
                StructureNodeKind.TENANT,
                null,
                false,
                "n",
                null,
                emptyList(),
                1,
                StructureEnvironment.SANDBOX,
            )
        assertThat(node.environment).isEqualTo(StructureEnvironment.SANDBOX)
    }
}
