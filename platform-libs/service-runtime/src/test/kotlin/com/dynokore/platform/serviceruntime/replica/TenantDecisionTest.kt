package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.reflect.Modifier
import java.time.Duration

/**
 * INV-AC-11706, INV-Q-11688 A: an operation that names no entity is permitted only on a TENANT-scope grant of
 * the key, decided locally; beyond the lag bound the answer is AUTHORITY_UNAVAILABLE for every principal kind.
 */
class TenantDecisionTest {
    private val clock = MutableClock()
    private val access =
        FakeFallback<AuthorizationRequest, AuthorityVerdict> { FallbackResult.Found(AuthorityVerdict(true)) }
    private val key = "privacy-request.handle"

    // the fixture lists one role for the key, and another role for a second key (a role the action does not list)
    private val roles =
        OperationRoleMap {
            when (it) {
                key -> mapOf("officer" to CellAllowance.PERMITTED)
                "privacy-request.close" -> mapOf("closer" to CellAllowance.PERMITTED)
                else -> emptyMap()
            }
        }
    private val replica = AuthorityReplica(clock, roles, fallback = access)
    private val feed = FakeFeed(replica)
    private val from = clock.instant().minusSeconds(60)
    private val tenantGrant = RoleGrant("officer", GrantScope.TENANT, TENANT_A.toString(), from)
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
    private val unavailable = AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)

    private fun principal(
        kind: PrincipalKind,
        id: String = "p1",
    ) = if (kind == PrincipalKind.MACHINE) {
        Principal.machine(id, TENANT_A, MachineEntityCoverage.None, "filing-workflow")
    } else {
        Principal(id, kind)
    }

    private fun ask(
        action: String = key,
        tenant: TenantId = TENANT_A,
        id: String = "p1",
        kind: PrincipalKind = PrincipalKind.HUMAN,
    ) = replica.decideTenant(tenant, principal(kind, id), action)

    private fun give(
        v: Long,
        grant: RoleGrant,
        ref: String = "g1",
        state: RoleGrantState = RoleGrantState.ACTIVE,
    ) = feed.publish(AuthorityChanged(TENANT_A, "p1", v, roleGrantRecord(ref, grant, state)))

    @Test
    fun `INV-AC-11706 a TENANT grant of the key gives GRANTED local`() {
        give(1, tenantGrant)
        ask() shouldBe granted
        access.calls shouldBe 0
    }

    @ParameterizedTest
    @EnumSource(PrincipalKind::class)
    fun `INV-AC-11706 every principal kind is decided on the grant alone`(kind: PrincipalKind) {
        give(1, tenantGrant)
        ask(kind = kind) shouldBe granted
    }

    @ParameterizedTest
    @EnumSource(
        value = GrantScope::class,
        names = ["GROUP", "ENTITY", "ENTITY_PERIOD", "CONSENT", "CLIENT_ENTITY", "INSTALLATION", "UNKNOWN"],
    )
    fun `INV-AC-11706 a grant at another scope level gives NO_GRANT`(level: GrantScope) {
        give(1, tenantGrant.copy(scope = level))
        feed.publish(
            AuthorityChanged(
                TENANT_A,
                "p1",
                2,
                AuthorityRecord.ConsentRecord(
                    "e1",
                    ConsentState.LIVE,
                    setOf(ConsentScopeItem.SUBMIT_INVOICE_DATA),
                    from,
                ),
            ),
        )
        ask() shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 a wire scope level TENANTS gives NO_GRANT`() {
        give(1, tenantGrant.copy(scope = GrantScope.of("TENANTS")))
        ask() shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 a TENANT grant of tenant A asked for tenant B gives NO_GRANT`() {
        give(1, tenantGrant)
        ask(tenant = TENANT_B) shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 a TENANT grant of a role the action does not list gives NO_GRANT`() {
        give(1, tenantGrant.copy(role = "closer"))
        ask() shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 an expired and a not yet valid TENANT grant give NO_GRANT`() {
        give(1, tenantGrant.copy(validUntil = clock.instant().minusSeconds(1)))
        ask() shouldBe noGrant
        give(2, tenantGrant.copy(validFrom = clock.instant().plusSeconds(5)))
        ask() shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 a TENANT grant voided by a principal record gives REVOKED`() {
        give(1, tenantGrant)
        feed.publish(AuthorityChanged(TENANT_A, "p1", 2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))
        ask() shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)
        give(3, tenantGrant) // reinstated
        ask() shouldBe granted
    }

    @Test
    fun `INV-AC-11706 a voided record does not hide a live TENANT grant in a newer record`() {
        give(1, tenantGrant, ref = "g1")
        feed.publish(AuthorityChanged(TENANT_A, "p1", 2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))
        give(3, tenantGrant, ref = "g2")
        ask() shouldBe granted
    }

    @Test
    fun `INV-AC-11706 a grant removed by a REMOVED record gives NO_GRANT`() {
        give(1, tenantGrant)
        give(2, tenantGrant, state = RoleGrantState.REMOVED)
        ask() shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 no record at all gives NO_GRANT`() {
        feed.heartbeat(1)
        feed.snapshotComplete(1)
        ask() shouldBe noGrant
    }

    @ParameterizedTest
    @EnumSource(PrincipalKind::class)
    fun `INV-AC-11706 lag beyond the bound gives AUTHORITY_UNAVAILABLE and no fallback call`(kind: PrincipalKind) {
        give(1, tenantGrant)
        feed.heartbeat(2)
        clock.advance(Duration.ofSeconds(1))
        ask(kind = kind) shouldBe granted
        clock.advance(Duration.ofMillis(1))
        ask(kind = kind) shouldBe unavailable
        access.calls shouldBe 0
    }

    @ParameterizedTest
    @EnumSource(PrincipalKind::class)
    fun `INV-AC-11706 no version received yet gives AUTHORITY_UNAVAILABLE and no fallback call`(kind: PrincipalKind) {
        val cold = AuthorityReplica(clock, roles, fallback = access)
        cold.decideTenant(TENANT_A, principal(kind), key) shouldBe unavailable
        access.calls shouldBe 0
    }

    @Test
    fun `INV-AC-11706 look-alike actions and principal ids are refused`() {
        give(1, tenantGrant)
        for (action in listOf("$key ", "Privacy-request.handle", "$key.", "")) ask(action = action) shouldBe noGrant
        for (id in listOf("p1 ", "P1", "")) ask(id = id) shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 decideTenant is the only public member added to AuthorityReplica`() {
        val names =
            AuthorityReplica::class.java.methods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .filter { it.declaringClass == AuthorityReplica::class.java }
                .filterNot { '$' in it.name } // Kotlin `internal`: JVM name has the module (INV-T-9232)
                .map { it.name.substringBefore('-') } // a TenantId parameter mangles the JVM name
                .toSortedSet()
        names shouldBe
            sortedSetOf(
                "accept",
                "decide",
                "decideTenant",
                "getBreakGlassSessions",
                "getConsents",
                "getKeySets",
                "getLagBound",
                "getLinkSessionKeys", // INV-T-17803: the link-session key set
                "getMachineAccessKeys",
                "getOperatorSessionKeys",
                "getPrincipals",
                "getRoleGrants", // INV-T-9232: the role-grant store
                "getTracker",
                "heldEntities",
                "holdersAt", // INV-T-9232
                "holdersOn", // INV-T-9232
                "lag",
                "observeHead",
                "onDisconnected",
                "onSnapshotComplete",
            )
    }
}
