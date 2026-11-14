package com.dynokore.gst.consoleBff.adapters.deferral

import com.dynokore.gst.consoleBff.application.deferral.DeferPermissions
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-21162 (authority half): the replica is never caught up, so decisions go to a scripted recording fallback. */
class ReplicaDeferAuthorityTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-14T10:00:00Z")
    }

    private val clock = FixedClock()
    private val tenant = "11111111-1111-1111-1111-111111111111"
    private val seen = mutableListOf<AuthorizationRequest>()

    private fun authority(answer: (AuthorizationRequest) -> FallbackResult<AuthorityVerdict>) =
        ReplicaDeferAuthority(
            AuthorityReplica(
                clock,
                operationRoles = OperationRoleMap { emptyMap() },
                fallback =
                    ReplicaFallback { request, _ ->
                        seen += request
                        answer(request)
                    },
            ),
            clock,
            Duration.ofSeconds(1),
        )

    @Test
    fun `INV-AC-21162 holds is true for an allow and the request carries tenant principal key and entity E1`() {
        authority { FallbackResult.Found(AuthorityVerdict(true)) }
            .holds(tenant, "user-1", "E1", DeferPermissions.EXCEPTION_DEFER) shouldBe true
        seen.size shouldBe 1
        val request = seen.single()
        request.tenant shouldBe TenantId.of(tenant)
        request.principal.id shouldBe "user-1"
        request.principal.kind shouldBe PrincipalKind.HUMAN
        request.entityRef shouldBe "E1"
        request.action shouldBe DeferPermissions.EXCEPTION_DEFER
    }

    @Test
    fun `INV-AC-21162 the permission argument is sent unchanged and no role name is sent`() {
        val odd = "exception.defer "
        authority { FallbackResult.Found(AuthorityVerdict(true)) }.holds(tenant, "u", "E1", odd) shouldBe true
        seen.single().action shouldBe odd
        seen.clear()
        authority { FallbackResult.Found(AuthorityVerdict(true)) }.holds(tenant, "u", "E1", "other.key")
        seen.single().action shouldBe "other.key"
        listOf("tax.operator", "admin", "operator").none { seen.single().action.contains(it) } shouldBe true
    }

    @Test
    fun `INV-AC-21162 holds is false for a deny and for a decision that cannot be made`() {
        val key = DeferPermissions.EXCEPTION_DEFER
        authority { FallbackResult.Found(AuthorityVerdict(false)) }.holds(tenant, "u", "E1", key) shouldBe false
        authority { FallbackResult.NotFound }.holds(tenant, "u", "E1", key) shouldBe false
        authority { FallbackResult.Unavailable }.holds(tenant, "u", "E1", key) shouldBe false
    }

    @Test
    fun `INV-AC-21162 holds is false when decide throws and no exception escapes`() {
        authority { error("authority down") }.holds(tenant, "u", "E1", DeferPermissions.EXCEPTION_DEFER) shouldBe false
        seen.size shouldBe 1
        authority { error("x") }.holds("not-a-tenant", "u", "E1", DeferPermissions.EXCEPTION_DEFER) shouldBe false
    }

    @Test
    fun `INV-AC-21162 a replica that allows only another entity gives false for E1`() {
        val onlyE2 = { r: AuthorizationRequest -> FallbackResult.Found(AuthorityVerdict(r.entityRef == "E2")) }
        authority(onlyE2).holds(tenant, "u", "E1", DeferPermissions.EXCEPTION_DEFER) shouldBe false
        authority(onlyE2).holds(tenant, "u", "E2", DeferPermissions.EXCEPTION_DEFER) shouldBe true
    }
}
