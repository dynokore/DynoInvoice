package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.LocalDate

/**
 * INV-REQ-1201, INV-REQ-1199: local within the bound, access-svc beyond it, deny persons but
 * keep machines when both fail. INV-Q-3322, INV-Q-8972 A: role grant, consent and principal records, each versioned
 * on its own key; an action is decided from the roles the fixture [TEST_ROLES] lists for it.
 * INV-Q-3339, INV-AC-1911: entity-plus-period grants.
 */
@Suppress("LargeClass") // one fixture shared by the grant, lag and machine-type cases (CS-50)
class AuthorityReplicaTest {
    private val clock = MutableClock()
    private val access =
        FakeFallback<AuthorizationRequest, AuthorityVerdict> { FallbackResult.Found(AuthorityVerdict(true)) }
    private val replica = AuthorityReplica(clock, TEST_ROLES, fallback = access)
    private val feed = FakeFeed(replica)
    private val grant = RoleGrant("approver", GrantScope.ENTITY, "e1", clock.instant().minusSeconds(60))

    private fun request(
        kind: PrincipalKind,
        period: DateSpan? = null,
        entity: String = "e1",
    ) = AuthorizationRequest(
        TENANT_A,
        // A MACHINE principal is made only inside service-runtime (INV-Q-9028 A).
        if (kind == PrincipalKind.MACHINE) {
            Principal.machine("p1", TENANT_A, MachineEntityCoverage.None, "filing-workflow")
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
        held: RoleGrant = grant,
        ref: String = "g1",
    ) = record(v, roleGrantRecord(ref, held))

    private fun removeGrant(
        v: Long,
        ref: String = "g1",
    ) = record(v, roleGrantRecord(ref, grant, RoleGrantState.REMOVED))

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
        val otherAction = AuthorizationRequest(TENANT_A, human, "e1", "delete") // a role the action does not list
        replica.decide(otherAction, deadline(clock)) shouldBe
            AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        val otherTenant = AuthorizationRequest(TENANT_B, human, "e1", "approve")
        replica.decide(otherTenant, deadline(clock)).allowed shouldBe false
        access.calls shouldBe 0
    }

    @Test
    fun `grant state is keyed per entity`() {
        grantTo(1)
        grantTo(2, grant.copy(scopeRef = "e2"), ref = "g2")
        human().allowed shouldBe true
        human(entity = "e2").allowed shouldBe true
        human(entity = "e3").reason shouldBe DecisionReason.NO_GRANT
        removeGrant(3, ref = "g2") // e2 removed; e1 untouched
        human().allowed shouldBe true
        human(entity = "e2").reason shouldBe DecisionReason.NO_GRANT
    }

    @Test
    fun `a revocation and an expired grant deny locally`() {
        grantTo(1)
        record(2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED))
        decide(PrincipalKind.HUMAN) shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)
        val expiring = grant.copy(validUntil = clock.instant().plusSeconds(10))
        grantTo(3, expiring) // a reinstatement re-emits with a higher version
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
    fun `a removed grant is a tombstone, an older record cannot bring the grant back`() {
        grantTo(3)
        removeGrant(4)
        grantTo(3)
        human().reason shouldBe DecisionReason.NO_GRANT
        replica.roleGrants.size() shouldBe 0
    }

    @Test
    fun `a stateless event leaves the principal denied and drops what it held`() {
        grantTo(1)
        grantTo(2, grant.copy(scopeRef = "e2"), ref = "g2")
        record(3, roleGrantRecord("g1", grant), principal = "p2")
        record(4, null)
        human().allowed shouldBe false
        human(entity = "e2").allowed shouldBe false
        grantTo(2) // a redelivery from before the stateless event stays denied
        human().allowed shouldBe false
        replica.roleGrants.peek(RoleGrantKey(TENANT_A, "p2", "g1")) shouldBe grant
        access.calls shouldBe 0
    }

    @Test
    fun `a late stateless event with a lower version keeps each key's version, a removed grant stays removed`() {
        grantTo(20)
        removeGrant(25) // removed at v25
        record(10, null) // late, lower version, no record
        grantTo(20) // redelivered
        human().reason shouldBe DecisionReason.NO_GRANT
        grantTo(26)
        human().allowed shouldBe true
    }

    @Test
    fun `a consent-derived grant needs a live consent record`() {
        val viaConsent = grant.copy(scope = GrantScope.CONSENT, scopeRef = "c1")
        grantTo(1, viaConsent)
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
        grantTo(1, grant.copy(scope = GrantScope.CONSENT, scopeRef = "c1"))
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
        grantTo(1, grant.copy(scope = GrantScope.CONSENT, scopeRef = "c1"))
        val from = clock.instant().minusSeconds(60)
        val expired = AuthorityRecord.ConsentRecord("e1", ConsentState.LIVE, emptySet(), from, clock.instant())
        record(2, expired)
        human().reason shouldBe DecisionReason.NO_LIVE_CONSENT
        grantTo(3, grant, ref = "g2") // a second, direct grant of the same role needs no consent
        human().allowed shouldBe true
    }

    @Test
    fun `a Q3 document is refused under a Q1-Q2 ENTITY_PERIOD grant still inside its validity window`() {
        val q1q2 =
            grant.copy(
                scope = GrantScope.ENTITY_PERIOD,
                validUntil = clock.instant().plus(Duration.ofDays(365)),
                periodFrom = LocalDate.parse("2027-01-01"),
                periodTo = LocalDate.parse("2027-06-30"),
            )
        grantTo(1, q1q2)
        human(span("2027-04-01", "2027-06-30")).allowed shouldBe true
        human(span("2027-01-01", "2027-06-30")).allowed shouldBe true
        human(span("2027-07-01", "2027-09-30")).reason shouldBe DecisionReason.NO_GRANT
        human(span("2026-12-01", "2027-02-28")).allowed shouldBe false // straddles the start
    }

    @Test
    fun `a request without a period is refused under a period grant, a plain ENTITY grant covers every period`() {
        val period =
            grant.copy(
                scope = GrantScope.ENTITY_PERIOD,
                periodFrom = LocalDate.parse("2027-01-01"),
                periodTo = LocalDate.parse("2027-06-30"),
            )
        grantTo(1, period)
        human().allowed shouldBe false
        grantTo(2, period.copy(periodFrom = null)) // a period grant without its span matches nothing
        human(span("2027-01-01", "2027-03-31")).allowed shouldBe false
        grantTo(3, grant)
        human().allowed shouldBe true
        human(span("2030-01-01", "2030-03-31")).allowed shouldBe true
    }

    @Test
    fun `an unknown scopeLevel is refused, never read as an entity grant`() {
        GrantScope.of("ENTITY_PROJECT") shouldBe GrantScope.UNKNOWN
        GrantScope.of("ENTITY_PERIOD") shouldBe GrantScope.ENTITY_PERIOD
        grantTo(1, grant.copy(scope = GrantScope.of("ENTITY_PROJECT")))
        human() shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        human(span("2027-01-01", "2027-03-31")).allowed shouldBe false
    }

    @Test
    fun `with no version received yet decisions go to access-svc`() {
        val cold = AuthorityReplica(clock, TEST_ROLES, fallback = access)
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
        val restarted = AuthorityReplica(clock, TEST_ROLES, fallback = access)
        val resumed = FakeFeed(restarted, synced = false)
        // p1 was granted earlier in the log; the resumed feed only delivers p2's change.
        resumed.publish(AuthorityChanged(TENANT_A, "p2", 900, roleGrantRecord("g1", grant, RoleGrantState.REMOVED)))
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
        // The type is listed for the action, so the request reaches the degraded answer (INV-AC-13561).
        val listed =
            AuthorityReplica(clock, { mapOf("machine.filing-workflow" to CellAllowance.PERMITTED) }, fallback = access)
        val listedFeed = FakeFeed(listed)
        listedFeed.publish(AuthorityChanged(TENANT_A, "p1", 1, roleGrantRecord("g1", grant)))
        listedFeed.drop()
        clock.advance(Duration.ofSeconds(30))
        access.answer = { FallbackResult.Unavailable }
        listed.decide(request(PrincipalKind.MACHINE), deadline(clock)) shouldBe
            AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
    }

    @Test
    fun `a cold replica with no fallback configured denies persons and unmarked machine requests`() {
        val cold = AuthorityReplica(clock, TEST_ROLES)
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

    // INV-AC-13561: a MACHINE request is decided from the token type and the map; roles below are test data.
    private val machineRoles =
        OperationRoleMap { key ->
            when (key) {
                "machine.drain.run" -> {
                    mapOf(
                        "machine.filing-workflow" to CellAllowance.PERMITTED,
                        "machine.monitoring" to CellAllowance.PERMITTED,
                    )
                }

                "machine.submissions.read" -> {
                    mapOf(
                        "machine.reporting" to CellAllowance.PERMITTED,
                        "machine.filing-workflow" to CellAllowance.PERMITTED,
                    )
                }

                else -> {
                    emptyMap()
                }
            }
        }

    private fun machineReplica(current: Boolean): AuthorityReplica {
        val r =
            AuthorityReplica(
                clock,
                machineRoles,
                fallback =
                    FakeFallback<AuthorizationRequest, AuthorityVerdict> {
                        FallbackResult.Unavailable
                    },
            )
        val f = FakeFeed(r)
        f.publish(AuthorityChanged(TENANT_A, "other", 1, null))
        f.heartbeat(1)
        f.snapshotComplete(1)
        if (!current) {
            f.drop()
            clock.advance(Duration.ofSeconds(30))
        }
        return r
    }

    private fun machine(
        type: String?,
        action: String,
        current: Boolean,
        entity: String = "E-001",
    ): AuthorityDecision {
        val r = machineReplica(current)
        val principal =
            if (type == null) {
                Principal("m1", PrincipalKind.HUMAN)
            } else {
                Principal.machine("m1", TENANT_A, MachineEntityCoverage.Entity("E-001"), type)
            }
        return r.decide(AuthorizationRequest(TENANT_A, principal, entity, action), deadline(clock))
    }

    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

    @Test
    fun `INV-AC-13561 filing-workflow is granted for its covered entity and denied for another`() {
        machine("filing-workflow", "machine.drain.run", true) shouldBe granted
        machine("filing-workflow", "machine.drain.run", true, "E-002") shouldBe noGrant
    }

    @Test
    fun `INV-AC-13561 reporting is NO_GRANT on drain with the replica current and stale`() {
        machine("reporting", "machine.drain.run", true) shouldBe noGrant
        machine("reporting", "machine.drain.run", false) shouldBe noGrant
    }

    @Test
    fun `INV-AC-13561 ingest is NO_GRANT on submissions read`() {
        machine("ingest", "machine.submissions.read", true) shouldBe noGrant
    }

    @Test
    fun `INV-AC-13561 any listed role is enough`() {
        machine("monitoring", "machine.drain.run", true) shouldBe granted
        machine("reporting", "machine.submissions.read", true) shouldBe granted
    }

    @Test
    fun `INV-AC-13561 a listed role beyond the lag bound keeps the degraded answer`() {
        // no degraded-allow table: the existing degraded path answers, never a local grant
        machine("filing-workflow", "machine.drain.run", false) shouldBe
            AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["sandbox-runner", "Filing-Workflow", "filing-workflow ", "", "machine.filing-workflow"])
    fun `INV-AC-13561 an unlisted or look-alike type is NO_GRANT`(type: String) {
        machine(type, "machine.drain.run", true) shouldBe noGrant
    }

    @ParameterizedTest
    @ValueSource(strings = ["machine.filing-workflow", "machine.drain.run ", "Machine.drain.run"])
    fun `INV-AC-13561 an action that is a role name or has a stray character is NO_GRANT`(action: String) {
        machine("filing-workflow", action, true) shouldBe noGrant
    }

    @Test
    fun `INV-AC-13561 a decision has the properties allowed, source and reason only`() {
        AuthorityDecision::class.java.declaredFields
            .filter {
                !it.isSynthetic &&
                    !java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
            }.map { it.name }
            .toSet() shouldBe setOf("allowed", "source", "reason")
    }
}
