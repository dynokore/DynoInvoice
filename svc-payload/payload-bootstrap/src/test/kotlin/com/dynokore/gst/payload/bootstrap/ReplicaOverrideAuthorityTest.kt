package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.application.override.VALIDATION_OVERRIDE
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.util.UUID

/**
 * INV-Q-17175 A, INV-Q-10027: [ReplicaOverrideAuthority] holds a scoped permission only when the replica allows it from
 * the source LOCAL or FALLBACK. The replica is a fake that answers per request, so every source can be produced.
 */
class ReplicaOverrideAuthorityTest {
    private val tenant = TenantId(UUID.nameUUIDFromBytes("tenant-a".toByteArray()))
    private val tenants =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            // the mangled name of a function returning the value class TenantId: it returns the wrapped UUID
            check(method.name.startsWith("current-")) { "only current() is read, was ${method.name}" }
            tenant.value
        } as TenantContext

    private val seen = mutableListOf<AuthorizationRequest>()

    private fun authority(answer: (AuthorizationRequest) -> AuthorityDecision) =
        ReplicaOverrideAuthority({ request, _: Deadline ->
            seen += request
            answer(request)
        }, tenants, Clock.systemUTC())

    private fun decision(
        allowed: Boolean,
        source: DecisionSource,
        reason: DecisionReason = if (allowed) DecisionReason.GRANTED else DecisionReason.NO_GRANT,
    ) = AuthorityDecision(allowed, source, reason)

    @Test
    fun `allowed from LOCAL holds`() {
        authority { decision(true, DecisionSource.LOCAL) }.holds("u1", VALIDATION_OVERRIDE, "E-001") shouldBe true
    }

    @Test
    fun `allowed from FALLBACK holds`() {
        authority { decision(true, DecisionSource.FALLBACK) }.holds("u1", VALIDATION_OVERRIDE, "E-001") shouldBe true
    }

    @Test
    fun `allowed from DEGRADED does not hold`() {
        authority {
            decision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
        }.holds("u1", VALIDATION_OVERRIDE, "E-001") shouldBe false
    }

    @Test
    fun `a denial from LOCAL does not hold`() {
        authority { decision(false, DecisionSource.LOCAL) }.holds("u1", VALIDATION_OVERRIDE, "E-001") shouldBe false
    }

    @Test
    fun `a revoked person does not hold`() {
        authority {
            decision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)
        }.holds("u1", VALIDATION_OVERRIDE, "E-001") shouldBe false
    }

    @Test
    fun `a lag beyond the bound with no fallback does not hold`() {
        authority {
            decision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
        }.holds("u1", VALIDATION_OVERRIDE, "E-001") shouldBe false
    }

    @Test
    fun `the key validation override with a trailing space is asked as given and is refused`() {
        // the fake grants exactly the catalogue key, by string equality, as the replica does
        val authority = authority { decision(it.action == VALIDATION_OVERRIDE, DecisionSource.LOCAL) }
        authority.holds("u1", "$VALIDATION_OVERRIDE ", "E-001") shouldBe false
        authority.holds("u1", VALIDATION_OVERRIDE, "E-001") shouldBe true
    }

    @Test
    fun `the request carries the tenant, the person as a HUMAN, the entity and the key and no role`() {
        authority { decision(true, DecisionSource.LOCAL) }.holds("u1", VALIDATION_OVERRIDE, "E-001")
        val request = seen.single()
        request.tenant shouldBe tenant
        request.principal.id shouldBe "u1"
        request.principal.kind shouldBe PrincipalKind.HUMAN
        request.entityRef shouldBe "E-001"
        request.action shouldBe "validation.override"
    }
}
