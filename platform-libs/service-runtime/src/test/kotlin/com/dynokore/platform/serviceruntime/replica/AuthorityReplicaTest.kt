package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.LocalDate

/**
 * INV-REQ-1201, INV-REQ-1199: local within the bound, access-svc beyond it, deny persons but
 * keep machines when both fail. INV-Q-3322: grant, consent and principal records, each versioned
 * on its own key. INV-Q-3339, INV-AC-1911: entity-plus-period grants.
 */
class AuthorityReplicaTest {
    private val clock = MutableClock()
    private val access =
        FakeFallback<AuthorizationRequest, AuthorityVerdict> { FallbackResult.Found(AuthorityVerdict(true)) }
    private val replica = AuthorityReplica(clock, fallback = access)
    private val feed = FakeFeed(replica)
    private val grant = Grant("approve", ScopeLevel.ENTITY, "e1", clock.instant().minusSeconds(60))

    private fun request(
        kind: PrincipalKind,
        period: DateSpan? = null,
        entity: String = "e1",
    ) = AuthorizationRequest(
        TENANT_A,
        // A MACHINE principal is made only inside service-runtime (INV-Q-9028 A).
        if (kind == PrincipalKind.MACHINE) {
            Principal.machine("p1", TENANT_A, MachineEntityCoverage.None)
        } else {
            Principal("p1", kind)
        },
        entity,
        "approve",
        period,
    )

    private fun decide(kind: PrincipalKind) = replica.decide(request(kind), deadline(clock))

    private fun human(
        period: DateSpan? = null,
        entity: String = "e1",
    ) = replica.decide(request(PrincipalKind.HUMAN, period, entity), deadline(clock))

    private fun record(
        v: Long,
        record: AuthorityRecord?,
        principal: String = "p1",
    ) = feed.publish(AuthorityChanged(TENANT_A, principal, v, record))

    private fun grantTo(
        v: Long,
        grants: List<Grant> = listOf(grant),
        entity: String = "e1",
    ) = record(v, AuthorityRecord.GrantRecord(entity, grants))

    private fun consent(
        v: Long,
        state: ConsentState,
        entity: String = "e1",
    ) = record(
        v,
        AuthorityRecord.ConsentRecord(entity, state, setOf(submit), clock.instant().minusSeconds(60)),
    )

    private val submit = ConsentScopeItem.SUBMIT_INVOICE_DATA

    private fun span(
        from: String,
        to: String,
    ) = DateSpan(LocalDate.parse(from), LocalDate.parse(to))

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
    fun `grant state is keyed per entity`() {
        grantTo(1)
        grantTo(2, listOf(grant.copy(scopeRef = "e2")), entity = "e2")
        human().allowed shouldBe true
        human(entity = "e2").allowed shouldBe true
        human(entity = "e3").reason shouldBe DecisionReason.NO_GRANT
        grantTo(3, entity = "e2", grants = emptyList()) // e2 removed; e1 untouched
        human().allowed shouldBe true
        human(entity = "e2").reason shouldBe DecisionReason.NO_GRANT
    }

    @Test
    fun `a revocation and an expired grant deny locally`() {
        grantTo(1)
        record(2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED))
        decide(PrincipalKind.HUMAN) shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)
        val expiring = grant.copy(validUntil = clock.instant().plusSeconds(10))
        grantTo(3, listOf(expiring)) // a reinstatement re-emits with a higher version
        decide(PrincipalKind.HUMAN).allowed shouldBe true
        clock.advance(Duration.ofSeconds(10))
        feed.heartbeat(3)
        decide(PrincipalKind.HUMAN).allowed shouldBe false
    }

    @Test
    fun `a redelivered older grant after a revocation stays denied`() {
        grantTo(5)
        record(6, AuthorityRecord.PrincipalRecord(PrincipalState.DELETED))
        grantTo(5) // redelivered
        grantTo(4) // older still
        human().reason shouldBe DecisionReason.REVOKED
        grantTo(7) // reinstated: only a newer record grants
        human().allowed shouldBe true
    }

    @Test
    fun `an empty grant list is a tombstone, an older record cannot bring the grant back`() {
        grantTo(3)
        grantTo(4, grants = emptyList())
        grantTo(3)
        human().reason shouldBe DecisionReason.NO_GRANT
        replica.grants.size() shouldBe 0
    }

    @Test
    fun `a stateless event leaves the principal denied and drops what it held`() {
        grantTo(1)
        grantTo(2, listOf(grant.copy(scopeRef = "e2")), entity = "e2")
        record(3, AuthorityRecord.GrantRecord("e1", listOf(grant)), principal = "p2")
        record(4, null)
        human().allowed shouldBe false
        human(entity = "e2").allowed shouldBe false
        grantTo(2) // a redelivery from before the stateless event stays denied
        human().allowed shouldBe false
        replica.grants.peek(AuthorityKey(TENANT_A, "p2", "e1")) shouldBe GrantState(listOf(grant), 3)
        access.calls shouldBe 0
    }

    @Test
    fun `a late stateless event with a lower version keeps each key's version, a removed grant stays removed`() {
        grantTo(20)
        grantTo(25, grants = emptyList()) // removed at v25
        record(10, null) // late, lower version, no record
        grantTo(20) // redelivered
        human().reason shouldBe DecisionReason.NO_GRANT
        grantTo(26)
        human().allowed shouldBe true
    }

    @Test
    fun `a consent-derived grant needs a live consent record`() {
        val viaConsent = grant.copy(scopeLevel = ScopeLevel.CONSENT, scopeRef = "c1")
        grantTo(1, listOf(viaConsent))
        human().reason shouldBe DecisionReason.NO_LIVE_CONSENT
        consent(2, ConsentState.LIVE)
        human().allowed shouldBe true
        consent(3, ConsentState.WITHDRAWN) // no grant event follows
        human() shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_LIVE_CONSENT)
        consent(2, ConsentState.LIVE) // a redelivered older consent changes nothing
        human().allowed shouldBe false
        consent(4, ConsentState.UNKNOWN)
        human().allowed shouldBe false
    }

    @Test
    fun `INV-AC-1946 a consent-derived request needs the scope item it names on the live consent`() {
        grantTo(1, listOf(grant.copy(scopeLevel = ScopeLevel.CONSENT, scopeRef = "c1")))
        consent(2, ConsentState.LIVE) // lists only SUBMIT_INVOICE_DATA

        fun asking(item: ConsentScopeItem?) =
            replica.decide(
                AuthorizationRequest(
                    TENANT_A,
                    Principal("p1", PrincipalKind.CONSENT_AGENT),
                    "e1",
                    "approve",
                    null,
                    item,
                ),
                deadline(clock),
            )
        asking(ConsentScopeItem.FILE_GST_RETURN) shouldBe
            AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.CONSENT_SCOPE_MISSING)
        asking(ConsentScopeItem.SUBMIT_INVOICE_DATA).allowed shouldBe true
        asking(null).allowed shouldBe true // no item asked: grant and live consent decide
        val filing = setOf(ConsentScopeItem.FILE_GST_RETURN, ConsentScopeItem.of("FUTURE_ITEM"))
        record(3, AuthorityRecord.ConsentRecord("e1", ConsentState.LIVE, filing, clock.instant().minusSeconds(60)))
        asking(ConsentScopeItem.FILE_GST_RETURN).allowed shouldBe true
        asking(ConsentScopeItem.SUBMIT_INVOICE_DATA).allowed shouldBe false
        asking(ConsentScopeItem.of("FUTURE_ITEM")).allowed shouldBe false // an unknown item never matches
        consent(4, ConsentState.WITHDRAWN)
        asking(ConsentScopeItem.FILE_GST_RETURN).reason shouldBe DecisionReason.NO_LIVE_CONSENT
    }

    @Test
    fun `a required scope item does not limit a direct grant`() {
        grantTo(1)
        val request =
            AuthorizationRequest(
                TENANT_A,
                Principal("p1", PrincipalKind.HUMAN),
                "e1",
                "approve",
                requiredScopeItem = ConsentScopeItem.FILE_GST_RETURN,
            )
        replica.decide(request, deadline(clock)).allowed shouldBe true
    }

    @Test
    fun `a consent outside its validity window is not live, a direct grant needs no consent`() {
        grantTo(1, listOf(grant.copy(scopeLevel = ScopeLevel.CONSENT, scopeRef = "c1")))
        val from = clock.instant().minusSeconds(60)
        val expired = AuthorityRecord.ConsentRecord("e1", ConsentState.LIVE, emptySet(), from, clock.instant())
        record(2, expired)
        human().reason shouldBe DecisionReason.NO_LIVE_CONSENT
        grantTo(3, listOf(grant.copy(scopeLevel = ScopeLevel.CONSENT, scopeRef = "c1"), grant))
        human().allowed shouldBe true
    }

    @Test
    fun `a Q3 document is refused under a Q1-Q2 ENTITY_PERIOD grant still inside its validity window`() {
        val q1q2 =
            grant.copy(
                scopeLevel = ScopeLevel.ENTITY_PERIOD,
                validUntil = clock.instant().plus(Duration.ofDays(365)),
                periodFrom = LocalDate.parse("2027-01-01"),
                periodTo = LocalDate.parse("2027-06-30"),
            )
        grantTo(1, listOf(q1q2))
        human(span("2027-04-01", "2027-06-30")).allowed shouldBe true
        human(span("2027-01-01", "2027-06-30")).allowed shouldBe true
        human(span("2027-07-01", "2027-09-30")).reason shouldBe DecisionReason.NO_GRANT
        human(span("2026-12-01", "2027-02-28")).allowed shouldBe false // straddles the start
    }

    @Test
    fun `a request without a period is refused under a period grant, a plain ENTITY grant covers every period`() {
        val period =
            grant.copy(
                scopeLevel = ScopeLevel.ENTITY_PERIOD,
                periodFrom = LocalDate.parse("2027-01-01"),
                periodTo = LocalDate.parse("2027-06-30"),
            )
        grantTo(1, listOf(period))
        human().allowed shouldBe false
        grantTo(2, listOf(period.copy(periodFrom = null))) // a period grant without its span matches nothing
        human(span("2027-01-01", "2027-03-31")).allowed shouldBe false
        grantTo(3, listOf(grant))
        human().allowed shouldBe true
        human(span("2030-01-01", "2030-03-31")).allowed shouldBe true
    }

    @Test
    fun `an unknown scopeLevel is refused, never read as an entity grant`() {
        ScopeLevel.of("ENTITY_PROJECT") shouldBe ScopeLevel.UNKNOWN
        ScopeLevel.of("ENTITY_PERIOD") shouldBe ScopeLevel.ENTITY_PERIOD
        grantTo(1, listOf(grant.copy(scopeLevel = ScopeLevel.of("ENTITY_PROJECT"))))
        human() shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        human(span("2027-01-01", "2027-03-31")).allowed shouldBe false
    }

    @Test
    fun `with no version received yet decisions go to access-svc`() {
        val cold = AuthorityReplica(clock, fallback = access)
        cold.decide(request(PrincipalKind.HUMAN), deadline(clock)).source shouldBe DecisionSource.FALLBACK
        access.calls shouldBe 1
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
    fun `after a restart the feed resuming mid-log does not make an empty replica decide locally`() {
        val restarted = AuthorityReplica(clock, fallback = access)
        val resumed = FakeFeed(restarted, synced = false)
        // p1 was granted earlier in the log; the resumed feed only delivers p2's change.
        resumed.publish(AuthorityChanged(TENANT_A, "p2", 900, AuthorityRecord.GrantRecord("e1", emptyList())))
        resumed.heartbeat(900)
        restarted.lag() shouldBe Lag.Unknown
        restarted.decide(request(PrincipalKind.HUMAN), deadline(clock)).source shouldBe DecisionSource.FALLBACK
        access.calls shouldBe 1
        resumed.snapshotComplete(900)
        restarted.decide(request(PrincipalKind.HUMAN), deadline(clock)).source shouldBe DecisionSource.LOCAL
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
    fun `a machine request with no marked operation is denied when the replica is stale and access-svc is down`() {
        grantTo(1)
        feed.drop()
        clock.advance(Duration.ofSeconds(30))
        access.answer = { FallbackResult.Unavailable }
        decide(PrincipalKind.MACHINE) shouldBe
            AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
    }

    @Test
    fun `a cold replica with no fallback configured denies persons and unmarked machine requests`() {
        val cold = AuthorityReplica(clock)
        cold.decide(request(PrincipalKind.HUMAN), deadline(clock)).allowed shouldBe false
        cold.decide(request(PrincipalKind.MACHINE), deadline(clock)).allowed shouldBe false
    }

    @Test
    fun `a definite fallback deny is final even for a machine`() {
        grantTo(1)
        feed.drop()
        clock.advance(Duration.ofSeconds(30))
        access.answer = { FallbackResult.Found(AuthorityVerdict(false)) }
        decide(PrincipalKind.MACHINE).allowed shouldBe false
    }

    @Test
    fun `with heartbeats expected, silence of 1000 ms is local and 1001 ms goes to access-svc`() {
        replica.tracker.expectHeartbeats(Duration.ofMillis(1000))
        grantTo(1)
        clock.advance(Duration.ofMillis(1000))
        decide(PrincipalKind.HUMAN).source shouldBe DecisionSource.LOCAL
        access.calls shouldBe 0
        clock.advance(Duration.ofMillis(1))
        decide(PrincipalKind.HUMAN).source shouldBe DecisionSource.FALLBACK
        access.calls shouldBe 1
    }
}
