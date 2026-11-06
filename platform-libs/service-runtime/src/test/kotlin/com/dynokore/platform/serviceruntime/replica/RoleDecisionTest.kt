package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.time.Clock
import java.time.Duration

/**
 * INV-T-9233, INV-Q-8972 A: an action is decided from the roles the [OperationRoleMap] lists for it, over the role
 * grants the replica holds (INV-REQ-864, INV-REQ-1214); levels, caller kinds and the decision record carry no role.
 */
class RoleDecisionTest {
    private val clock = MutableClock()
    private val structure = StructureReplica(clock)
    private val roles =
        OperationRoleMap {
            when (it) {
                "approve" -> mapOf("approver" to CellAllowance.PERMITTED, "reviewer" to CellAllowance.WITHIN_SCOPE)
                "tenant.act" -> mapOf("approver" to CellAllowance.PERMITTED, "reviewer" to CellAllowance.WITHIN_SCOPE)
                else -> emptyMap()
            }
        }
    private val replica = AuthorityReplica(clock, roles, structure = structure)
    private val feed = FakeFeed(replica)
    private val from = clock.instant().minusSeconds(60)
    private var version = 0L
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
    private val tenantRef = TENANT_A.toString()

    private fun grant(
        role: String,
        scope: GrantScope = GrantScope.ENTITY,
        ref: String? = "e1",
        principal: String = "p1",
        tenant: TenantId = TENANT_A,
        grantRef: String = "$role-$scope-$ref",
        state: RoleGrantState = RoleGrantState.ACTIVE,
    ) = feed.publish(
        AuthorityChanged(
            tenant,
            principal,
            ++version,
            roleGrantRecord(grantRef, RoleGrant(role, scope, ref, from), state),
        ),
    )

    private fun node(
        ref: String,
        kind: StructureNodeKind,
        parent: String? = null,
        tenant: TenantId = TENANT_A,
        retired: Boolean = false,
    ) = structure.replica.apply(
        ReplicaChange.Upsert(
            StructureKey(tenant, ref),
            StructureNode(kind, parent, retired, ref, null, emptyList(), 1),
            1,
        ),
    )

    private fun decide(
        action: String = "approve",
        entity: String = "e1",
        tenant: TenantId = TENANT_A,
        principal: Principal = Principal("p1", PrincipalKind.HUMAN),
        level: ScopeLevel = ScopeLevel.ENTITY,
    ) = replica.decide(AuthorizationRequest(tenant, principal, entity, action, level = level), deadline(clock))

    @Test
    fun `a role not listed for the action gives NO_GRANT`() {
        grant("clerk")
        decide() shouldBe noGrant
    }

    @Test
    fun `any one listed role is enough, the first need not be held`() {
        grant("reviewer")
        decide() shouldBe granted
    }

    @Test
    fun `a principal who holds two roles, only one of them listed, is granted`() {
        grant("clerk")
        grant("approver")
        decide() shouldBe granted
        decide("delete") shouldBe noGrant
    }

    @Test
    fun `an action that is a role name or has a trailing space gives NO_GRANT for a holder of every role`() {
        for (role in listOf("approver", "reviewer", "clerk")) grant(role)
        for (action in listOf("approver", "reviewer", "approve ", " approve", "APPROVE", "")) {
            decide(action) shouldBe noGrant
        }
    }

    @Test
    fun `a role given through a WITHIN_SCOPE cell is granted inside the grant's scope and not outside it`() {
        grant("reviewer", GrantScope.ENTITY, "e1")
        decide(entity = "e1") shouldBe granted
        decide(entity = "e2") shouldBe noGrant
        grant("reviewer", GrantScope.GROUP, "grp")
        node("e2", StructureNodeKind.ENTITY, "grp")
        node("grp", StructureNodeKind.GROUP)
        decide(entity = "e2") shouldBe granted
        decide(entity = "e3") shouldBe noGrant
    }

    @Test
    fun `a TENANT grant covers an entity of the tenant and not an entity of another tenant`() {
        grant("approver", GrantScope.TENANT, tenantRef)
        decide(entity = "any-entity") shouldBe granted
        decide(tenant = TENANT_B) shouldBe noGrant
    }

    @Test
    fun `a GROUP grant covers an entity of the group by the structure replica and not one outside it`() {
        node("grp", StructureNodeKind.GROUP)
        node("inside", StructureNodeKind.ENTITY, "grp")
        node("outside", StructureNodeKind.ENTITY, "other-grp")
        node("other-grp", StructureNodeKind.GROUP)
        grant("approver", GrantScope.GROUP, "grp")
        decide(entity = "inside") shouldBe granted
        decide(entity = "outside") shouldBe noGrant
        decide(entity = "unknown-node") shouldBe noGrant
    }

    @Test
    fun `a grant removed by a REMOVED record gives NO_GRANT`() {
        grant("approver", grantRef = "g1")
        decide() shouldBe granted
        grant("approver", grantRef = "g1", state = RoleGrantState.REMOVED)
        decide() shouldBe noGrant
    }

    @Test
    fun `an ENTITY_PERIOD grant matches a period inside its span and a null period matches none`() {
        feed.publish(
            AuthorityChanged(
                TENANT_A,
                "p1",
                ++version,
                roleGrantRecord(
                    "g1",
                    RoleGrant(
                        "approver",
                        GrantScope.ENTITY_PERIOD,
                        "e1",
                        from,
                        periodFrom = java.time.LocalDate.parse("2027-01-01"),
                        periodTo = java.time.LocalDate.parse("2027-06-30"),
                    ),
                ),
            ),
        )

        fun at(period: DateSpan?) =
            replica.decide(
                AuthorizationRequest(TENANT_A, Principal("p1", PrincipalKind.HUMAN), "e1", "approve", period),
                deadline(clock),
            )
        at(DateSpan(java.time.LocalDate.parse("2027-02-01"), java.time.LocalDate.parse("2027-03-31"))) shouldBe granted
        at(DateSpan(java.time.LocalDate.parse("2027-07-01"), java.time.LocalDate.parse("2027-09-30"))) shouldBe noGrant
        at(null) shouldBe noGrant
    }

    @Test
    fun `an ENTITY_PERIOD grant of another entity matches nothing`() {
        feed.publish(
            AuthorityChanged(
                TENANT_A,
                "p1",
                ++version,
                roleGrantRecord(
                    "g1",
                    RoleGrant(
                        "approver",
                        GrantScope.ENTITY_PERIOD,
                        "e9",
                        from,
                        periodFrom = java.time.LocalDate.parse("2027-01-01"),
                        periodTo = java.time.LocalDate.parse("2027-06-30"),
                    ),
                ),
            ),
        )
        val period = DateSpan(java.time.LocalDate.parse("2027-02-01"), java.time.LocalDate.parse("2027-03-31"))
        replica.decide(
            AuthorizationRequest(TENANT_A, Principal("p1", PrincipalKind.HUMAN), "e1", "approve", period),
            deadline(clock),
        ) shouldBe noGrant
    }

    @Test
    fun `a CONSENT grant reads the live consent on every call`() {
        grant("approver", GrantScope.CONSENT, "c1")
        val agent = Principal("p1", PrincipalKind.CONSENT_AGENT)
        decide(principal = agent).reason shouldBe DecisionReason.NO_LIVE_CONSENT
        feed.publish(
            AuthorityChanged(
                TENANT_A,
                "p1",
                ++version,
                AuthorityRecord.ConsentRecord("e1", ConsentState.LIVE, setOf(ConsentScopeItem.FILE_GST_RETURN), from),
            ),
        )
        decide(principal = agent) shouldBe granted
        feed.publish(
            AuthorityChanged(
                TENANT_A,
                "p1",
                ++version,
                AuthorityRecord.ConsentRecord("e1", ConsentState.WITHDRAWN, emptySet(), from),
            ),
        )
        decide(principal = agent) shouldBe
            AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_LIVE_CONSENT)
    }

    @Test
    fun `the decision uses only the principal named in the request`() {
        val worker = Principal.machine("worker-1", TENANT_A, MachineEntityCoverage.Tenant(tenantRef))
        grant("approver", principal = "worker-1")
        decide(principal = Principal("user-1", PrincipalKind.HUMAN)) shouldBe noGrant
        decide(principal = worker) shouldBe granted
    }

    @Test
    fun `a holder of a listed role at INSTALLATION is granted at level INSTALLATION for any tenant, not at TENANT`() {
        grant("approver", GrantScope.INSTALLATION, null, tenant = TenantId.PLATFORM)
        decide(tenant = TENANT_A, level = ScopeLevel.INSTALLATION) shouldBe granted
        decide(tenant = TENANT_B, level = ScopeLevel.INSTALLATION) shouldBe granted
        decide(tenant = TENANT_A, level = ScopeLevel.TENANT) shouldBe noGrant
        decide(level = ScopeLevel.ENTITY) shouldBe noGrant
    }

    @Test
    fun `a TENANT grant is granted at TENANT and GROUP and not at INSTALLATION`() {
        grant("approver", GrantScope.TENANT, tenantRef)
        decide(level = ScopeLevel.TENANT) shouldBe granted
        decide(level = ScopeLevel.GROUP) shouldBe granted
        decide(level = ScopeLevel.INSTALLATION) shouldBe noGrant
        decide(tenant = TENANT_B, level = ScopeLevel.TENANT) shouldBe noGrant
    }

    @Test
    fun `an ENTITY grant gets NO_GRANT at TENANT and a WITHIN_SCOPE cell needs a TENANT grant there`() {
        grant("approver", GrantScope.ENTITY, "e1")
        decide(level = ScopeLevel.TENANT) shouldBe noGrant
        grant("reviewer", GrantScope.ENTITY, "e1", principal = "p2")
        decide(level = ScopeLevel.TENANT, principal = Principal("p2", PrincipalKind.HUMAN)) shouldBe noGrant
        grant("reviewer", GrantScope.TENANT, tenantRef, principal = "p3")
        decide(level = ScopeLevel.TENANT, principal = Principal("p3", PrincipalKind.HUMAN)) shouldBe granted
    }

    @Test
    fun `a request built with the default level equals the same request with ENTITY`() {
        val human = Principal("p1", PrincipalKind.HUMAN)
        AuthorizationRequest(TENANT_A, human, "e1", "approve") shouldBe
            AuthorizationRequest(TENANT_A, human, "e1", "approve", level = ScopeLevel.ENTITY)
    }

    @Test
    fun `entityRef is ignored at the levels that have no entity`() {
        grant("approver", GrantScope.TENANT, tenantRef)
        decide(entity = "", level = ScopeLevel.TENANT) shouldBe granted
        decide(entity = "zzz", level = ScopeLevel.GROUP) shouldBe granted
    }

    @Test
    fun `a voided grant gives REVOKED and a newer grant is held`() {
        grant("approver")
        feed.publish(
            AuthorityChanged(TENANT_A, "p1", ++version, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)),
        )
        decide() shouldBe AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)
        grant("approver", grantRef = "again")
        decide() shouldBe granted
    }

    @Test
    fun `a legacy event leaves the role grants as they were and counts as received for the lag`() {
        grant("approver")
        feed.heartbeat(version + 5)
        clock.advance(Duration.ofSeconds(5))
        replica.lag().isWithin(replica.lagBound) shouldBe false
        feed.publish(AuthorityChanged(TENANT_A, "p1", version + 5, AuthorityRecord.LegacyGrant("e1")))
        replica.lag().isWithin(replica.lagBound) shouldBe true
        replica.tracker.appliedVersion() shouldBe version + 5
        decide() shouldBe granted
        replica.roleGrants.size() shouldBe 1
    }

    @Test
    fun `the decision record has the three properties allowed, source and reason`() {
        AuthorityDecision::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet() shouldBe setOf("allowed", "source", "reason")
    }

    @Test
    fun `the operation role map is a required constructor parameter`() {
        val synthetic =
            AuthorityReplica::class.java.constructors.single {
                it.parameterTypes.lastOrNull()?.simpleName == "DefaultConstructorMarker"
            }
        val args = arrayOfNulls<Any>(synthetic.parameterCount)
        args[0] = Clock.systemUTC()
        args[synthetic.parameterCount - 2] = 0b1111111110 // every parameter but the clock takes its default
        val failure = shouldThrow<InvocationTargetException> { synthetic.newInstance(*args) }
        (failure.targetException is NullPointerException) shouldBe true
    }

    @Test
    fun `heldEntities gives the entities of valid role grants by scope`() {
        node("e1", StructureNodeKind.ENTITY, "grp")
        node("e2", StructureNodeKind.ENTITY, "grp")
        node("e3", StructureNodeKind.ENTITY)
        node("e4", StructureNodeKind.ENTITY, "grp", retired = true)
        node("e5", StructureNodeKind.ENTITY, tenant = TENANT_B)
        node("grp", StructureNodeKind.GROUP)
        grant("approver", GrantScope.ENTITY, "x1", principal = "one")
        replica.heldEntities(TENANT_A, "one") shouldBe setOf("x1")
        grant("approver", GrantScope.GROUP, "grp", principal = "grouped")
        replica.heldEntities(TENANT_A, "grouped") shouldBe setOf("e1", "e2")
        grant("approver", GrantScope.TENANT, tenantRef, principal = "all")
        replica.heldEntities(TENANT_A, "all") shouldBe setOf("e1", "e2", "e3")
        replica.heldEntities(TENANT_B, "all") shouldBe emptySet()
        grant("approver", GrantScope.ENTITY, "x1", principal = "gone", grantRef = "g")
        grant("approver", GrantScope.ENTITY, "x1", principal = "gone", grantRef = "g", state = RoleGrantState.REMOVED)
        replica.heldEntities(TENANT_A, "gone") shouldBe emptySet()
    }

    @Test
    fun `heldEntities gives none for an expired grant and counts only that tenant's nodes in the visit counter`() {
        node("e1", StructureNodeKind.ENTITY)
        node("e5", StructureNodeKind.ENTITY, tenant = TENANT_B)
        node("e6", StructureNodeKind.ENTITY, tenant = TENANT_B)
        feed.publish(
            AuthorityChanged(
                TENANT_A,
                "late",
                ++version,
                roleGrantRecord(
                    "g",
                    RoleGrant("approver", GrantScope.TENANT, tenantRef, from, clock.instant().minusSeconds(1)),
                ),
            ),
        )
        replica.heldEntities(TENANT_A, "late") shouldBe emptySet()
        val before = structure.replica.visitCount
        structure.entitiesOf(TENANT_A).keys shouldBe setOf("e1")
        structure.replica.visitCount - before shouldBe 1
    }
}
