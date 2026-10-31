package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import com.dynokore.platform.configresolver.ScopeLevel as Level

/** INV-Q-8972 A, INV-Q-8913 A, INV-AC-2544, INV-AC-1911: the role grants and the role answers read from them. */
class RoleGrantReplicaTest {
    private val clock = MutableClock()
    private val structure = StructureReplica(clock)
    private val replica =
        AuthorityReplica(
            clock,
            AuthorityReplica.DEFAULT_LAG_BOUND,
            null,
            DegradedAllowTable.EMPTY,
            { _, _ -> false },
            { },
            BreakGlassCounter.NONE,
            null,
            null,
            structure,
        )
    private val feed = FakeFeed(replica)
    private val from = clock.instant().minusSeconds(60)
    private var version = 0L

    private fun grant(
        scope: GrantScope,
        ref: String?,
        role: String = ADMIN,
    ) = RoleGrant(role, scope, ref, from)

    private fun publish(
        grant: RoleGrant,
        ref: String = "g1",
        principal: String = "p1",
        tenant: TenantId = TENANT_A,
        state: RoleGrantState = RoleGrantState.ACTIVE,
        v: Long = ++version,
    ) = feed.publish(AuthorityChanged(tenant, principal, v, AuthorityRecord.RoleGrantRecord(ref, state, grant)))

    private fun roles(
        level: Level,
        entity: String? = null,
        tenant: TenantId = TENANT_A,
        principal: String = "p1",
    ) = replica.rolesAt(tenant, principal, level, null, entity, deadline(clock))

    private fun node(
        ref: String,
        kind: StructureNodeKind,
        parent: String?,
    ) = structure.replica.apply(
        ReplicaChange.Upsert(
            StructureKey(TENANT_A, ref),
            StructureNode(kind, parent, false, ref, null, emptyList(), 1),
            1,
        ),
    )

    private fun held(ref: String = "g1") = replica.roleGrants.peek(RoleGrantKey(TENANT_A, "p1", ref))

    @Test
    fun `scope and state wire values match by exact name`() {
        GrantScope.of("CLIENT_ENTITY") shouldBe GrantScope.CLIENT_ENTITY
        GrantScope.of("tenant") shouldBe GrantScope.UNKNOWN
        GrantScope.of("TENANT ") shouldBe GrantScope.UNKNOWN
        GrantScope.of("UNKNOWN") shouldBe GrantScope.UNKNOWN
        RoleGrantState.of("ACTIVE") shouldBe RoleGrantState.ACTIVE
        RoleGrantState.of("active") shouldBe RoleGrantState.UNKNOWN
    }

    @Test
    fun `upsert, newer replaces, older redelivery ignored`() {
        val a = grant(GrantScope.TENANT, TENANT_A.toString())
        publish(a, v = 5)
        held() shouldBe a
        val b = a.copy(role = "entity.admin")
        publish(b, v = 7)
        held() shouldBe b
        publish(a, v = 6)
        held() shouldBe b
    }

    @Test
    fun `removal keeps the version so an older upsert stays out, and an unknown state removes`() {
        val a = grant(GrantScope.TENANT, TENANT_A.toString())
        publish(a, v = 5)
        publish(a, state = RoleGrantState.REMOVED, v = 8)
        held() shouldBe null
        publish(a, v = 7)
        held() shouldBe null
        publish(a, v = 9)
        held() shouldBe a
        publish(a, state = RoleGrantState.of("GONE"), v = 10)
        held() shouldBe null
    }

    @Test
    fun `a principal record voids lower versions and a re-grant at a higher version is held`() {
        val a = grant(GrantScope.TENANT, TENANT_A.toString())
        publish(a, v = 3)
        feed.publish(AuthorityChanged(TENANT_A, "p1", 5, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))
        roles(Level.TENANT) shouldBe emptySet()
        publish(a, v = 6)
        roles(Level.TENANT) shouldBe setOf(ADMIN)
    }

    @Test
    fun `a record-less event tombstones the principal's role grants only`() {
        val a = grant(GrantScope.TENANT, TENANT_A.toString())
        publish(a, v = 3)
        publish(a, principal = "p2", v = 4)
        publish(a, tenant = TENANT_B, v = 5)
        feed.publish(AuthorityChanged(TENANT_A, "p1", 6, null))
        held() shouldBe null
        publish(a, v = 2)
        held() shouldBe null
        roles(Level.TENANT, principal = "p2") shouldBe setOf(ADMIN)
        replica.roleGrants.peek(RoleGrantKey(TENANT_B, "p1", "g1")) shouldBe a
    }

    @Test
    fun `the same grantRef of another principal and tenant is untouched`() {
        val a = grant(GrantScope.TENANT, TENANT_A.toString())
        publish(a, v = 3)
        publish(a, state = RoleGrantState.REMOVED, principal = "p2", v = 4)
        publish(a, state = RoleGrantState.REMOVED, tenant = TENANT_B, v = 5)
        held() shouldBe a
    }

    @Test
    fun `tenant admin answers at TENANT, GROUP and ENTITY and not elsewhere`() {
        publish(grant(GrantScope.TENANT, TENANT_A.toString()))
        setOf(Level.TENANT, Level.GROUP, Level.ENTITY).forEach { roles(it, "e9") shouldBe setOf(ADMIN) }
        roles(Level.TENANT, tenant = TENANT_B) shouldBe emptySet()
        roles(Level.INSTALLATION) shouldBe emptySet()
    }

    @Test
    fun `an entity grant answers on its entity only`() {
        publish(grant(GrantScope.ENTITY, "e1"))
        roles(Level.ENTITY, "e1") shouldBe setOf(ADMIN)
        roles(Level.ENTITY, "e2") shouldBe emptySet()
        roles(Level.GROUP, "e1") shouldBe emptySet()
        roles(Level.TENANT) shouldBe emptySet()
    }

    @Test
    fun `a group grant answers at an entity under the group and never at level GROUP`() {
        node("e1", StructureNodeKind.ENTITY, "g")
        node("e3", StructureNodeKind.ENTITY, null)
        node("g", StructureNodeKind.GROUP, null)
        publish(grant(GrantScope.GROUP, "g"))
        roles(Level.ENTITY, "e1") shouldBe setOf(ADMIN)
        roles(Level.ENTITY, "e3") shouldBe emptySet()
        roles(Level.GROUP, "e1") shouldBe emptySet()
    }

    @Test
    fun `a missing node, a 17 step chain and a loop give no group grant`() {
        publish(grant(GrantScope.GROUP, "g"))
        roles(Level.ENTITY, "e1") shouldBe emptySet()
        node("e1", StructureNodeKind.ENTITY, "n1")
        for (i in 1..16) node("n$i", StructureNodeKind.BUSINESS_UNIT, "n${i + 1}")
        node("n17", StructureNodeKind.BUSINESS_UNIT, "g")
        node("g", StructureNodeKind.GROUP, null)
        roles(Level.ENTITY, "e1") shouldBe emptySet()
        structure.replica.apply(
            ReplicaChange.Upsert(
                StructureKey(TENANT_A, "n15"),
                StructureNode(StructureNodeKind.BUSINESS_UNIT, "g", false, "n15", null, emptyList(), 2),
                2,
            ),
        )
        roles(Level.ENTITY, "e1") shouldBe setOf(ADMIN)
        node("e5", StructureNodeKind.ENTITY, "e6")
        node("e6", StructureNodeKind.BUSINESS_UNIT, "e5")
        roles(Level.ENTITY, "e5") shouldBe emptySet()
    }

    @Test
    fun `an ENTITY_PERIOD grant answers nowhere`() {
        publish(grant(GrantScope.ENTITY_PERIOD, "e1"))
        Level.entries.forEach { roles(it, "e1") shouldBe emptySet() }
    }

    @Test
    fun `a consent grant answers only while the consent is live`() {
        publish(grant(GrantScope.CONSENT, "e1"))
        roles(Level.ENTITY, "e1") shouldBe emptySet()
        val live = AuthorityRecord.ConsentRecord("e1", ConsentState.LIVE, emptySet(), from)
        feed.publish(AuthorityChanged(TENANT_A, "agent", ++version, live))
        roles(Level.ENTITY, "e1") shouldBe setOf(ADMIN)
        feed.publish(AuthorityChanged(TENANT_A, "agent", ++version, live.copy(state = ConsentState.WITHDRAWN)))
        roles(Level.ENTITY, "e1") shouldBe emptySet()
    }

    @Test
    fun `a client entity grant answers at level CLIENT_ENTITY only`() {
        publish(grant(GrantScope.CLIENT_ENTITY, "c1"))
        roles(Level.CLIENT_ENTITY, "c1") shouldBe setOf(ADMIN)
        roles(Level.ENTITY, "c1") shouldBe emptySet()
        roles(Level.CLIENT_ENTITY, "c2") shouldBe emptySet()
    }

    @Test
    fun `an installation grant is stored under the platform tenant and answers for any tenant`() {
        publish(grant(GrantScope.INSTALLATION, null, "operator"), tenant = TenantId.PLATFORM)
        roles(Level.INSTALLATION, tenant = TENANT_A, principal = "p1") shouldBe setOf("operator")
        roles(Level.INSTALLATION, tenant = TENANT_B) shouldBe setOf("operator")
        roles(Level.TENANT) shouldBe emptySet()
    }

    @Test
    fun `expired, not yet valid and removed grants answer nothing`() {
        val t = grant(GrantScope.TENANT, TENANT_A.toString())
        publish(t.copy(validUntil = clock.instant().minusSeconds(1)), "g1")
        publish(t.copy(validFrom = clock.instant().plusSeconds(1)), "g2")
        publish(t, "g3")
        publish(t, "g3", state = RoleGrantState.REMOVED)
        roles(Level.TENANT) shouldBe emptySet()
    }

    @Test
    fun `role strings are compared by equality`() {
        publish(grant(GrantScope.TENANT, TENANT_A.toString(), "tenant.admin2"), "g1")
        publish(grant(GrantScope.TENANT, TENANT_A.toString(), "Tenant.Admin"), "g2")
        roles(Level.TENANT) shouldBe setOf("tenant.admin2", "Tenant.Admin")
        (ADMIN in roles(Level.TENANT)) shouldBe false
    }

    @Test
    fun `a stale replica and one with no snapshot refuse`() {
        publish(grant(GrantScope.TENANT, TENANT_A.toString()))
        feed.heartbeat(version + 10)
        clock.advance(Duration.ofSeconds(5))
        shouldThrow<RoleAnswerUnavailable> { roles(Level.TENANT) }
        val cold = AuthorityReplica(clock)
        shouldThrow<RoleAnswerUnavailable> { cold.rolesAt(TENANT_A, "p1", Level.TENANT, null, null, deadline(clock)) }
    }

    @Test
    fun `holders are the principals whose current grant carries the role`() {
        val t = grant(GrantScope.TENANT, TENANT_A.toString(), SECURITY)
        publish(t, "g1", "a")
        publish(t, "g1", "b")
        publish(t, "g1", "c")
        publish(t, "g1", "removed")
        publish(t, "g1", "removed", state = RoleGrantState.REMOVED)
        publish(t.copy(validUntil = clock.instant().minusSeconds(1)), "g1", "expired")
        publish(t, "g1", "voided")
        feed.publish(
            AuthorityChanged(TENANT_A, "voided", ++version, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)),
        )
        publish(t, "g1", "other", tenant = TENANT_B)
        publish(t.copy(scopeRef = TENANT_B.toString()), "g2", "wrongref")
        val before = replica.roleGrants.visitCount
        replica.holdersAt(TENANT_A, SECURITY, Level.TENANT) shouldBe setOf("a", "b", "c")
        (replica.roleGrants.visitCount - before) shouldBe 6L
        replica.holdersAt(TENANT_A, "security.officer2", Level.TENANT) shouldBe emptySet()
        replica.holdersAt(TENANT_A, "Security.Officer", Level.TENANT) shouldBe emptySet()
        replica.holdersAt(TENANT_A, SECURITY, Level.INSTALLATION) shouldBe emptySet()
    }

    @Test
    fun `an ignored older event leaves the holder index unchanged`() {
        val t = grant(GrantScope.TENANT, TENANT_A.toString(), SECURITY)
        publish(t, "g1", "a", v = 5)
        publish(t.copy(role = ADMIN), "g1", "a", v = 3)
        replica.holdersAt(TENANT_A, SECURITY, Level.TENANT) shouldBe setOf("a")
        replica.holdersAt(TENANT_A, ADMIN, Level.TENANT) shouldBe emptySet()
        publish(t, "g1", "a", state = RoleGrantState.REMOVED, v = 4)
        replica.holdersAt(TENANT_A, SECURITY, Level.TENANT) shouldBe setOf("a")
    }

    @Test
    fun `holders over a million grants examine only the indexed keys and stay flat`() {
        val big = AuthorityReplica(clock)
        val bigFeed = FakeFeed(big)
        val t = grant(GrantScope.TENANT, TENANT_A.toString(), SECURITY)

        fun medianNanos(): Long {
            val times = LongArray(200)
            for (i in times.indices) {
                val start = System.nanoTime()
                big.holdersAt(TENANT_A, SECURITY, Level.TENANT)
                times[i] = System.nanoTime() - start
            }
            return times.sorted()[100]
        }

        fun load(
            tenant: TenantId,
            principal: String,
            grant: RoleGrant,
            v: Long,
            state: RoleGrantState = RoleGrantState.ACTIVE,
        ) = bigFeed.publish(
            AuthorityChanged(tenant, principal, v, AuthorityRecord.RoleGrantRecord("g", state, grant)),
        )
        val tenants = List(1000) { if (it == 0) TENANT_A else TenantId(UUID.randomUUID()) }
        listOf("a", "b", "c").forEach { load(TENANT_A, it, t, 1) }
        tenants.drop(1).take(5).forEach { load(it, "x", t.copy(scopeRef = it.toString()), 1) }
        load(TENANT_A, "removed", t, 1)
        load(TENANT_A, "removed", t, 2, RoleGrantState.REMOVED)
        val small = medianNanos()
        val filler = grant(GrantScope.TENANT, null, "viewer")
        var n = 0
        while (n < 1_000_000) {
            load(tenants[n % 1000], "u$n", filler, 1)
            n++
        }
        val before = big.roleGrants.visitCount
        big.holdersAt(TENANT_A, SECURITY, Level.TENANT) shouldBe setOf("a", "b", "c")
        (big.roleGrants.visitCount - before) shouldBe 3L
        big.holdersAt(TENANT_A, "security.officer2", Level.TENANT) shouldBe emptySet()
        big.holdersAt(TENANT_A, "Security.Officer", Level.TENANT) shouldBe emptySet()
        (medianNanos() <= 5 * small) shouldBe true
    }

    @Test
    fun `a role change moves the principal between the role entries`() {
        publish(grant(GrantScope.TENANT, TENANT_A.toString(), SECURITY), "g1", "a", v = 1)
        publish(grant(GrantScope.TENANT, TENANT_A.toString(), ADMIN), "g1", "a", v = 2)
        replica.holdersAt(TENANT_A, SECURITY, Level.TENANT) shouldBe emptySet()
        replica.holdersAt(TENANT_A, ADMIN, Level.TENANT) shouldBe setOf("a")
        feed.publish(AuthorityChanged(TENANT_A, "a", 3, null))
        replica.holdersAt(TENANT_A, ADMIN, Level.TENANT) shouldBe emptySet()
    }

    @Test
    fun `installation holders read the platform tenant whatever tenant is asked`() {
        publish(grant(GrantScope.INSTALLATION, null, "operator"), "g1", "op", TenantId.PLATFORM)
        replica.holdersAt(TENANT_B, "operator", Level.INSTALLATION) shouldBe setOf("op")
        replica.holdersAt(TENANT_B, "operator", Level.TENANT) shouldBe emptySet()
    }

    @Test
    fun `holdersOn follows the entity row`() {
        node("e1", StructureNodeKind.ENTITY, "g")
        node("g", StructureNodeKind.GROUP, null)
        publish(grant(GrantScope.TENANT, TENANT_A.toString(), SECURITY), "g1", "t")
        publish(grant(GrantScope.GROUP, "g", SECURITY), "g1", "grp")
        publish(grant(GrantScope.ENTITY, "e1", SECURITY), "g1", "ent")
        publish(grant(GrantScope.ENTITY, "e2", SECURITY), "g1", "other")
        publish(grant(GrantScope.ENTITY_PERIOD, "e1", SECURITY), "g1", "period")
        replica.holdersOn(TENANT_A, SECURITY, "e1", deadline(clock)) shouldBe setOf("t", "grp", "ent")
    }

    @Test
    fun `the public surface of the replica package has no role resolver and rolesAt is not public`() {
        val rolesAt = AuthorityReplica::class.java.methods.filter { it.name.startsWith("rolesAt") }
        // a Kotlin `internal` member is JVM-public under a module-mangled name; a public one would keep the plain name
        rolesAt.map { it.name.contains('$') } shouldBe listOf(true)
        runCatching { Class.forName("com.dynokore.platform.serviceruntime.replica.ReplicaPolicyRoleResolver") }
            .isFailure shouldBe true
    }

    private companion object {
        const val ADMIN = "tenant.admin"
        const val SECURITY = "security.officer"
    }
}
