package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.util.UUID

/** INV-Q-17483: the request `ReplicaSetterPermission` builds (kind, entityRef) at each policy level. */
class ReplicaSetterPermissionTest {
    private val asked = mutableListOf<AuthorizationRequest>()
    private val tenant = TenantId(UUID.randomUUID())
    private val replica =
        AuthorityReplica(Clock.systemUTC(), OperationRoleMap { emptyMap() }, fallback = { request, _ ->
            asked += request
            FallbackResult.Found(AuthorityVerdict(true))
        })

    private fun permission(kind: PrincipalKind = PrincipalKind.HUMAN) =
        ReplicaSetterPermission(replica, Clock.systemUTC(), kind)

    private fun holds(
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        kind: PrincipalKind = PrincipalKind.HUMAN,
    ) = permission(kind).holds(Actor("u1"), tenant, level, scopeRef, entityRef, "vp-12.set")

    @ParameterizedTest
    @EnumSource(ScopeLevel::class, names = ["ENTITY", "FEED", "AP_CONNECTION", "CLIENT_ENTITY"])
    fun `an entity-scoped level sends the call's entityRef by string equality`(level: ScopeLevel) {
        holds(level, "scope-9", "E-1") shouldBe true
        asked.single().entityRef shouldBe "E-1"
    }

    @ParameterizedTest
    @EnumSource(ScopeLevel::class, names = ["TENANT", "GROUP", "INSTALLATION"])
    fun `a level without an entity sends the empty string and never the scopeRef`(level: ScopeLevel) {
        holds(level, "scope-9", "E-1") shouldBe true
        asked.single().entityRef shouldBe ""
        (asked.single().entityRef == "scope-9") shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ScopeLevel::class, names = ["ENTITY", "FEED", "AP_CONNECTION", "CLIENT_ENTITY"])
    fun `a null entityRef at an entity-scoped level is false and the replica is not asked`(level: ScopeLevel) {
        holds(level, "scope-9", null) shouldBe false
        asked.shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(ScopeLevel::class, names = ["ENTITY", "FEED", "AP_CONNECTION", "CLIENT_ENTITY"])
    fun `an empty entityRef at an entity-scoped level is false and the replica is not asked`(level: ScopeLevel) {
        holds(level, "scope-9", "") shouldBe false
        asked.shouldBeEmpty()
    }

    @Test
    fun `built with CONSENT_AGENT it sends CONSENT_AGENT`() {
        holds(ScopeLevel.ENTITY, "E-1", "E-1", PrincipalKind.CONSENT_AGENT) shouldBe true
        asked.single().principal.kind shouldBe PrincipalKind.CONSENT_AGENT
    }

    @Test
    fun `built with HUMAN it sends HUMAN`() {
        holds(ScopeLevel.ENTITY, "E-1", "E-1", PrincipalKind.HUMAN) shouldBe true
        asked.single().principal.kind shouldBe PrincipalKind.HUMAN
    }
}
