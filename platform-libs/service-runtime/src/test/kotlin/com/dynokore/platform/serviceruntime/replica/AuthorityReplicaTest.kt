package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration

/**
 * INV-REQ-1201, INV-REQ-1199: local within the bound, access-svc beyond it, deny persons but
 * keep machines when both fail.
 */
class AuthorityReplicaTest {
    private val clock = MutableClock()
    private val access =
        FakeFallback<AuthorizationRequest, AuthorityVerdict> { FallbackResult.Found(AuthorityVerdict(true)) }
    private val replica = AuthorityReplica(clock, fallback = access)
    private val feed = FakeFeed(replica)
    private val grant = Grant("e1", "approve", clock.instant().minusSeconds(60))

    private fun request(kind: PrincipalKind) = AuthorizationRequest(TENANT_A, Principal("p1", kind), "e1", "approve")

    private fun decide(kind: PrincipalKind) = replica.decide(request(kind), deadline(clock))

    private fun grantTo(v: Long) = feed.publish(AuthorityChanged(TENANT_A, "p1", PrincipalAuthority(listOf(grant)), v))

    @Test
    fun `within the bound the decision is local and access-svc is not called`() {
        grantTo(1)
        decide(PrincipalKind.HUMAN) shouldBe AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
        val human = Principal("p1", PrincipalKind.HUMAN)
        val otherAction = AuthorizationRequest(TENANT_A, human, "e1", "delete")
        replica.decide(otherAction, deadline(clock)) shouldBe
            AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        val otherTenant = AuthorizationRequest(TENANT_B, human, "e1", "approve")
        replica.decide(otherTenant, deadline(clock)).allowed shouldBe false
        access.calls shouldBe 0
    }

    @Test
    fun `a revocation and an expired grant deny locally`() {
        grantTo(1)
        feed.publish(AuthorityChanged(TENANT_A, "p1", PrincipalAuthority(listOf(grant), revoked = true), 2))
        decide(PrincipalKind.HUMAN) shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)
        val expiring = grant.copy(validUntil = clock.instant().plusSeconds(10))
        feed.publish(AuthorityChanged(TENANT_A, "p1", PrincipalAuthority(listOf(expiring)), 3))
        decide(PrincipalKind.HUMAN).allowed shouldBe true
        clock.advance(Duration.ofSeconds(10))
        feed.heartbeat(3)
        decide(PrincipalKind.HUMAN).allowed shouldBe false
        feed.publish(AuthorityChanged(TENANT_A, "p1", null, 4)) // principal removed
        decide(PrincipalKind.HUMAN).reason shouldBe DecisionReason.NO_GRANT
    }

    @Test
    fun `at exactly one second of lag it is still local, above it access-svc decides`() {
        grantTo(1)
        feed.heartbeat(2) // the source has moved on
        clock.advance(Duration.ofSeconds(1))
        decide(PrincipalKind.HUMAN).source shouldBe DecisionSource.LOCAL
        clock.advance(Duration.ofMillis(1))
        access.answer = { FallbackResult.Found(AuthorityVerdict(false)) }
        decide(PrincipalKind.HUMAN) shouldBe AuthorityDecision(false, DecisionSource.FALLBACK, DecisionReason.NO_GRANT)
        access.calls shouldBe 1
    }

    @Test
    fun `catching up returns to local decisions`() {
        grantTo(1)
        feed.heartbeat(2)
        clock.advance(Duration.ofSeconds(5))
        decide(PrincipalKind.HUMAN).source shouldBe DecisionSource.FALLBACK
        grantTo(2)
        decide(PrincipalKind.HUMAN).source shouldBe DecisionSource.LOCAL
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @EnumSource(value = PrincipalKind::class, names = ["HUMAN", "CONSENT_AGENT"])
    fun `a person is denied when the replica is stale and access-svc is down`(kind: PrincipalKind) {
        grantTo(1)
        feed.drop()
        clock.advance(Duration.ofSeconds(30))
        access.answer = { FallbackResult.Unavailable }
        decide(kind) shouldBe AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
    }

    @Test
    fun `a machine flow continues when the replica is stale and access-svc is down`() {
        grantTo(1)
        feed.drop()
        clock.advance(Duration.ofSeconds(30))
        access.answer = { FallbackResult.Unavailable }
        decide(PrincipalKind.MACHINE) shouldBe
            AuthorityDecision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
    }

    @Test
    fun `a cold replica with no fallback configured also denies persons and keeps machines`() {
        val cold = AuthorityReplica(clock)
        cold.decide(request(PrincipalKind.HUMAN), deadline(clock)).allowed shouldBe false
        cold.decide(request(PrincipalKind.MACHINE), deadline(clock)).allowed shouldBe true
    }

    @Test
    fun `a definite fallback deny is final even for a machine`() {
        grantTo(1)
        feed.drop()
        clock.advance(Duration.ofSeconds(30))
        access.answer = { FallbackResult.Found(AuthorityVerdict(false)) }
        decide(PrincipalKind.MACHINE).allowed shouldBe false
    }
}
