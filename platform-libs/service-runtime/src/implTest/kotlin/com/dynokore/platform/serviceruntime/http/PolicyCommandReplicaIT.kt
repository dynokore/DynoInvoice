package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.identity.OperatorSession
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration

/**
 * INV-T-10128 (INV-Q-10027, INV-Q-10025): without a `SetterPermission` bean the servlet binds
 * `AuthorityReplica.decide` for the permission key of the call; only `allowed` with source LOCAL or FALLBACK passes,
 * and DEGRADED, a denial and an exception are 403 SRT-004 with nothing stored. A cold replica answers through its
 * fallback.
 */
class PolicyCommandReplicaIT : PolicyHttpBase() {
    private val asked = mutableListOf<AuthorizationRequest>()

    private fun replica(answer: (AuthorizationRequest) -> FallbackResult<AuthorityVerdict>): AuthorityReplica {
        asked.clear() // the class instance is shared by its parameterised runs
        return AuthorityReplica(Clock.systemUTC(), OperationRoleMap { emptyMap() }, fallback = { request, _ ->
            asked += request
            answer(request)
        })
    }

    private fun Service.setTenant(token: String) =
        post(
            PolicyOp.SET.path("VP-12"),
            request(PolicyOp.SET, ScopeLevel.TENANT, tenant.toString()),
            token,
        )

    @OnPostgres
    fun `a FALLBACK allow stores the value, asked for the row's key at the call's level`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(true)) })
        s.setTenant(s.user("u1")).ok()
        s.stored() shouldBe 1
        asked.single().action shouldBe "vp-12.set"
        asked.single().level shouldBe ScopeLevel.TENANT
        asked.single().tenant shouldBe s.tenant
        asked.single().principal.id shouldBe "u1"
    }

    @OnPostgres
    fun `a denied decision is 403 SRT-004 with nothing stored`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(false)) })
        s.setTenant(s.user("u1")).closed(ClosedErrorCode.ACCESS_DENIED)
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `a DEGRADED decision is 403 SRT-004 with nothing stored`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Unavailable })
        s.setTenant(s.user("u1")).closed(ClosedErrorCode.ACCESS_DENIED)
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `a fallback that throws is 403 SRT-004 with nothing stored`(image: String) {
        val s = Service(image, authority = replica { error("access-svc down") })
        s.setTenant(s.user("u1")).closed(ClosedErrorCode.ACCESS_DENIED)
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `an acknowledgement is decided for its acknowledge key and a denial is 403 SRT-004`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(false)) })
        val body =
            request(
                PolicyOp.ACK,
                ScopeLevel.ENTITY,
                "E-1",
                "E-1",
                RequestFields(option = "NEXT_WORKING_DAY", asRole = "tax.approver"),
            )
        s.post(PolicyOp.ACK.path("VP-62"), body, s.user("u1", "E-1")).closed(ClosedErrorCode.ACCESS_DENIED)
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `a tenantless token at INSTALLATION is decided under the platform tenant`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(true)) })
        val body = request(PolicyOp.SET, ScopeLevel.INSTALLATION, selection = RequestFields(option = "Y"))
        s.post(PolicyOp.SET.path("VP-72"), body, s.operator("op", emptySet())).ok()
        asked.single().tenant shouldBe TenantId.PLATFORM
        asked.single().level shouldBe ScopeLevel.INSTALLATION
        asked.single().action shouldBe "vp-72.set"
    }

    @OnPostgres
    fun `an entity the current token does not cover is 403 SRT-004 and the replica is not asked`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(true)) })
        val body = request(PolicyOp.SET, ScopeLevel.ENTITY, "E-1", "E-1", RequestFields(option = "DAILY_WINDOW"))
        s.post(PolicyOp.SET.path("VP-20"), body, s.user("u1", "E-2")).closed(ClosedErrorCode.ACCESS_DENIED)
        asked.shouldBeEmpty()
        s.stored() shouldBe 0
    }

    /** A synced replica that decides locally for `vp-12.set` and holds [events] (version order). */
    private fun localReplica(vararg events: AuthorityChanged): AuthorityReplica {
        val replica =
            AuthorityReplica(
                Clock.systemUTC(),
                OperationRoleMap { if (it == "vp-12.set") mapOf("r1" to CellAllowance.PERMITTED) else emptyMap() },
            )
        replica.onSnapshotComplete(0)
        events.forEach(replica::accept)
        return replica
    }

    private fun grantEvent(
        s: Service,
        version: Long,
    ) = AuthorityChanged(
        s.tenant,
        "u1",
        version,
        AuthorityRecord.RoleGrantRecord(
            "g1",
            RoleGrantState.ACTIVE,
            RoleGrant("r1", GrantScope.TENANT, s.tenant.toString(), Clock.systemUTC().instant().minusSeconds(60)),
        ),
    )

    private fun reasonOf(
        replica: AuthorityReplica,
        s: Service,
    ) = replica
        .decide(
            AuthorizationRequest(
                s.tenant,
                Principal("u1", PrincipalKind.HUMAN),
                "",
                "vp-12.set",
                level = ScopeLevel.TENANT,
            ),
            Deadline.after(Duration.ofSeconds(2), Clock.systemUTC()),
        ).reason

    @OnPostgres
    fun `a REVOKED decision of the replica is 403 SRT-004 with nothing stored`(image: String) {
        val replica = localReplica()
        val s = Service(image, authority = replica)
        replica.accept(grantEvent(s, 1))
        replica.accept(AuthorityChanged(s.tenant, "u1", 2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))
        reasonOf(replica, s) shouldBe DecisionReason.REVOKED
        s.setTenant(s.user("u1")).closed(ClosedErrorCode.ACCESS_DENIED)
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `a NO_GRANT decision of the replica is 403 SRT-004 with nothing stored`(image: String) {
        val replica = localReplica()
        val s = Service(image, authority = replica)
        reasonOf(replica, s) shouldBe DecisionReason.NO_GRANT
        s.setTenant(s.user("u1")).closed(ClosedErrorCode.ACCESS_DENIED)
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `a LOCAL grant of the replica stores the value (the decision itself is proved in RoleDecisionTest)`(
        image: String,
    ) {
        val replica = localReplica()
        val s = Service(image, authority = replica)
        replica.accept(grantEvent(s, 1))
        s.setTenant(s.user("u1")).ok()
        s.stored() shouldBe 1
    }

    @OnPostgres
    fun `a service-supplied SetterPermission bean that answers false is 403 POL-004 and not SRT-004, nothing stored`(
        image: String,
    ) {
        val s = Service(image)
        s.setTenant(s.user("u1")).refused(PolicyErrorCode.ROLE_NOT_ALLOWED)
        s.portCalls.size shouldBe 1
        s.stored() shouldBe 0
    }

    private fun Service.atLevel(
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        token: String,
    ) = post(PolicyOp.SET.path("VP-12"), request(PolicyOp.SET, level, scopeRef, entityRef), token)

    private fun recorded(
        image: String,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        entities: Array<String> = emptyArray(),
    ): AuthorizationRequest {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(true)) })
        s
            .atLevel(
                level,
                scopeRef ?: s.tenant.toString().takeIf {
                    level == ScopeLevel.TENANT
                },
                entityRef,
                s.user("u1", *entities),
            ).ok()
        return asked.single()
    }

    @OnPostgres
    fun `ENTITY sends the call's entityRef`(image: String) {
        recorded(image, ScopeLevel.ENTITY, "E-1", "E-1", arrayOf("E-1")).entityRef shouldBe "E-1"
    }

    @OnPostgres
    fun `FEED sends the call's entityRef and not the feed scopeRef`(image: String) {
        val r = recorded(image, ScopeLevel.FEED, "feed-9", "E-1", arrayOf("E-1"))
        r.entityRef shouldBe "E-1"
        (r.entityRef == "feed-9") shouldBe false
    }

    @OnPostgres
    fun `AP_CONNECTION sends the call's entityRef and not the connection scopeRef`(image: String) {
        val r = recorded(image, ScopeLevel.AP_CONNECTION, "ap-7", "E-1", arrayOf("E-1"))
        r.entityRef shouldBe "E-1"
        (r.entityRef == "ap-7") shouldBe false
    }

    @OnPostgres
    fun `CLIENT_ENTITY sends the call's entityRef and not the client scopeRef`(image: String) {
        val r = recorded(image, ScopeLevel.CLIENT_ENTITY, "c-1", "E-1", arrayOf("E-1"))
        r.entityRef shouldBe "E-1"
        (r.entityRef == "c-1") shouldBe false
    }

    @OnPostgres
    fun `TENANT sends the empty entityRef and not the scopeRef`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(true)) })
        s.atLevel(ScopeLevel.TENANT, s.tenant.toString(), null, s.user("u1")).ok()
        asked.single().entityRef shouldBe ""
        (asked.single().entityRef == s.tenant.toString()) shouldBe false
    }

    @OnPostgres
    fun `GROUP sends the empty entityRef and not the scopeRef`(image: String) {
        val r = recorded(image, ScopeLevel.GROUP, "g-1", null)
        r.entityRef shouldBe ""
        (r.entityRef == "g-1") shouldBe false
    }

    @OnPostgres
    fun `INSTALLATION sends the empty entityRef`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(true)) })
        s.atLevel(ScopeLevel.INSTALLATION, null, null, s.operator("op", emptySet())).ok()
        asked.single().entityRef shouldBe ""
    }

    @OnPostgres
    fun `an end-user token sends the kind principalKindOf returns`(image: String) {
        recorded(image, ScopeLevel.GROUP, "g-1", null).principal.kind shouldBe PrincipalKind.HUMAN
    }

    @OnPostgres
    fun `an operator-session token sends the kind principalKindOf returns`(image: String) {
        val s = Service(image, authority = replica { FallbackResult.Found(AuthorityVerdict(true)) })
        val token = s.user("op", session = OperatorSession("platform.support", "CASE-5521"))
        s.atLevel(ScopeLevel.GROUP, "g-1", null, token).ok()
        asked.single().principal.kind shouldBe PrincipalKind.HUMAN
    }
}
