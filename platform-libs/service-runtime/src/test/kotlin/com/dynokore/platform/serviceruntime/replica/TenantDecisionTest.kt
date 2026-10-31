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
    private val replica = AuthorityReplica(clock, fallback = access)
    private val feed = FakeFeed(replica)
    private val key = "privacy-request.handle"
    private val from = clock.instant().minusSeconds(60)
    private val tenantGrant = Grant(key, ScopeLevel.TENANT, "a", from)
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
    private val unavailable = AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)

    private fun principal(
        kind: PrincipalKind,
        id: String = "p1",
    ) = if (kind == PrincipalKind.MACHINE) {
        Principal.machine(id, TENANT_A, MachineEntityCoverage.None)
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
        vararg grants: Grant,
        entity: String = "e1",
    ) = feed.publish(AuthorityChanged(TENANT_A, "p1", v, AuthorityRecord.GrantRecord(entity, grants.toList())))

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
    @EnumSource(value = ScopeLevel::class, names = ["GROUP", "ENTITY", "ENTITY_PERIOD", "CONSENT", "UNKNOWN"])
    fun `INV-AC-11706 a grant at another scope level gives NO_GRANT`(level: ScopeLevel) {
        give(1, tenantGrant.copy(scopeLevel = level))
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
        give(1, tenantGrant.copy(scopeLevel = ScopeLevel.of("TENANTS")))
        ask() shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 a TENANT grant of tenant A asked for tenant B gives NO_GRANT`() {
        give(1, tenantGrant)
        ask(tenant = TENANT_B) shouldBe noGrant
    }

    @Test
    fun `INV-AC-11706 a TENANT grant of another action gives NO_GRANT`() {
        give(1, tenantGrant.copy(action = "privacy-request.close"))
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
        give(1, tenantGrant, entity = "e1")
        feed.publish(AuthorityChanged(TENANT_A, "p1", 2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))
        give(3, tenantGrant, entity = "e2")
        ask() shouldBe granted
    }

    @Test
    fun `INV-AC-11706 a record replaced by one with no grants gives NO_GRANT`() {
        give(1, tenantGrant)
        give(2)
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
        val cold = AuthorityReplica(clock, fallback = access)
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
                .map { it.name.substringBefore('-') } // a TenantId parameter mangles the JVM name
                .toSortedSet()
        names shouldBe
            sortedSetOf(
                "accept",
                "decide",
                "decideTenant",
                "getBreakGlassSessions",
                "getConsents",
                "getGrants",
                "getKeySets",
                "getLagBound",
                "getMachineAccessKeys",
                "getOperatorSessionKeys",
                "getPrincipals",
                "getTracker",
                "heldEntities",
                "lag",
                "observeHead",
                "onDisconnected",
                "onSnapshotComplete",
            )
    }
}
