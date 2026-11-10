package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.adapters.http.PendingChangeAccepted
import com.dynokore.gst.tenancy.adapters.http.PendingChangeAdvice
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.PendingElectionChange
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val SG: ZoneId = ZoneId.of("Asia/Singapore")
private const val THREE_SOURCES =
    "[{\"sourceId\":\"MAS\",\"bases\":[\"DAILY\"]},{\"sourceId\":\"BNM\",\"bases\":[\"MONTHLY_AVERAGE\"]}," +
        "{\"sourceId\":\"ECB\",\"bases\":[\"DAILY\"]}]"
private const val AUDIT_TOPIC_NAME = "gst.platform.audit-entry-recorded.v1"
private val DAILY = RateSourceElectionRequest.Basis.DAILY
private val MONTHLY = RateSourceElectionRequest.Basis.MONTHLY_AVERAGE
private val LATER: LocalDate = TODAY.plusDays(30)

/** A clock a test moves; the use cases read it for the Singapore calendar day. */
private class MovingClock(
    start: Instant,
) : Clock() {
    @Volatile var now: Instant = start

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = fixed(now, zone)

    override fun instant(): Instant = now
}

/** The permission port of the lab: the three election-change keys go to [replicaAuth], the others to [people]. */
private class LabAuthority : ActionAuthority {
    lateinit var people: TestPeople

    @Volatile var replicaAuth: ActionAuthority? = null

    override fun holds(
        person: String,
        entityRef: String,
        action: String,
    ): Boolean =
        when (action) {
            ElectionPermissions.WITHDRAW, ElectionPermissions.LIST, ElectionPermissions.APPROVE -> {
                replicaAuth?.holds(person, entityRef, action) ?: false
            }

            else -> {
                people.holds(person, entityRef, action)
            }
        }
}

/** What a call answered over the HTTP surface: the status the problem renderer gives, the catalogue code, the body. */
private class Reply(
    val status: Int,
    val code: String?,
    val body: Any? = null,
)

private fun <T : Any> http(block: () -> ResponseEntity<T>): Reply =
    try {
        block().let { Reply(it.statusCode.value(), null, it.body) }
    } catch (accepted: PendingChangeAccepted) {
        val answer = PendingChangeAdvice().accepted(accepted)
        Reply(answer.statusCode.value(), null, answer.body)
    } catch (refusal: ApiRefusal) {
        Reply(Problems.render(refusal.code, "corr-test", refusal.detail).status, refusal.code.code)
    } catch (refusal: TenancyRefusal) {
        Reply(Problems.renderTenancy(refusal.code, "corr-test", refusal.detail).status, refusal.code.code)
    } catch (_: ConstraintRefusal) {
        Reply(422, "POL-014")
    }

private class ChangeLab(
    val env: ProfileEnv,
    val e: ElectionEnv,
    val clock: MovingClock,
    val auth: LabAuthority,
) {
    val people: TestPeople = e.people
    private val tenants: TenantContext = env.context.getBean(TenantContext::class.java)

    /** The production binding over the replica of [TestPeople]: the current tenant, the grants fed to it. */
    fun live(): ActionAuthority = ReplicaActionAuthority(people.replica, tenants, clock)

    fun tenantContext(): TenantContext = tenants

    private fun roleOf(
        key: String,
        avoid: Set<String> = emptySet(),
    ): String {
        val holders = TestOperationRoles.holders(key)
        assertThat(holders).describedAs("the catalogue lists a role for $key").isNotEmpty()
        return holders.sorted().firstOrNull { it !in avoid } ?: holders.sorted().first()
    }

    /** U1 holds the withdraw and list keys, U2 the approve and list keys, each by a role grant on the entity. */
    fun grantUsual(
        tenant: TenantId,
        entity: EntityResponse,
    ) {
        val site = GrantSite(tenant)
        val approvers = TestOperationRoles.holders(ElectionPermissions.APPROVE)
        people.grantRole(ADMIN, entity.entityRef, roleOf(ElectionPermissions.WITHDRAW, approvers), site = site)
        people.grantRole(ADMIN, entity.entityRef, roleOf(ElectionPermissions.LIST, approvers), site = site)
        people.grantRole(APPROVER, entity.entityRef, roleOf(ElectionPermissions.APPROVE), site = site)
        people.grantRole(APPROVER, entity.entityRef, roleOf(ElectionPermissions.LIST, emptySet()), site = site)
    }

    fun withdrawRole(): String =
        roleOf(ElectionPermissions.WITHDRAW, TestOperationRoles.holders(ElectionPermissions.APPROVE))

    /** An entity of a new tenant with the usual people, the scope pack of three sources and the grants of U1 and U2. */
    fun entity(block: (TenantId, EntityResponse) -> Unit) {
        env.withEntity { tenant, entity ->
            e.usualPeople(entity)
            e.staged(tenant, "SCOPE-SG", "1.0", sources = THREE_SOURCES)
            e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(30))
            e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.TRUE)
            auth.replicaAuth = live()
            grantUsual(tenant, entity)
            block(tenant, entity)
        }
    }

    fun asCaller(person: String) {
        people.caller = person
    }

    fun withdraw(
        entity: EntityResponse,
        changeRef: String,
    ) = http { env.api.withdrawElectionChange(entity.entityRef, changeRef, UUID.randomUUID()) }

    fun decline(
        entity: EntityResponse,
        changeRef: String,
    ) = http { env.api.declineElectionChange(entity.entityRef, changeRef, UUID.randomUUID()) }

    fun approve(
        entity: EntityResponse,
        changeRef: String,
    ) = http { env.api.approveElectionChange(entity.entityRef, changeRef, UUID.randomUUID()) }

    fun list(
        entity: EntityResponse,
        limit: Int = 50,
        cursor: String? = null,
    ) = http { env.api.listElectionChanges(entity.entityRef, limit, cursor) }

    fun rate(
        entity: EntityResponse,
        source: String,
        basis: RateSourceElectionRequest.Basis,
        from: LocalDate = LATER,
    ) = http {
        env.api.electRateSource(entity.entityRef, UUID.randomUUID(), RateSourceElectionRequest(source, basis, from))
    }

    fun elect(
        entity: EntityResponse,
        election: String,
        from: LocalDate = LATER,
    ) = http {
        env.api.recordElection(
            entity.entityRef,
            UUID.randomUUID(),
            com.dynokore.gst.tenancy.contract.model
                .ElectionRequest(election, from),
        )
    }

    /** The pending change a request answered 202. */
    fun pendingOf(reply: Reply): PendingElectionChange {
        assertThat(reply.status).describedAs("the request answers 202").isEqualTo(202)
        return reply.body as PendingElectionChange
    }

    /** The audit entries of the entity as text, read as the superuser. */
    fun auditTexts(entity: EntityResponse): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement("select data::text from tenancy.outbox where topic = ? and data::text like ?")
                .use { s ->
                    s.setString(1, AUDIT_TOPIC_NAME)
                    s.setString(2, "%${entity.entityRef}%")
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    fun at(day: LocalDate) {
        clock.now = day.atTime(12, 0).atZone(SG).toInstant()
    }
}

private fun changeLab(block: (ChangeLab) -> Unit) {
    val clock = MovingClock(Instant.now())
    val auth = LabAuthority()
    BoundaryFixture
        .runner()
        .withBean(Clock::class.java, { clock }, primaryBean())
        // TestPeople answers the caller and, as a fake, the permission keys; the lab decides the three election-change
        // keys by a real replica, so TestPeople stays the caller source only and its primary mark is lifted
        .withInitializer { ctx ->
            ctx.addBeanFactoryPostProcessor { factory -> factory.getBeanDefinition("testPeople").isPrimary = false }
        }.withBean("labActionAuthority", ActionAuthority::class.java, { auth }, primaryBean())
        .run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            val e = ElectionEnv(env)
            auth.people = e.people
            block(ChangeLab(env, e, clock, auth))
        }
}

/**
 * The lifecycle of a pending election change (withdraw, decline, list, the one-pending rule) over the HTTP surface of
 * the service: the controller of the real composition over PostgreSQL with forced row-level security, the response
 * rendered by the problem renderer, the permission keys decided by the real [AuthorityReplica] through
 * `ReplicaActionAuthority` (INV-Q-10027). INV-REQ-370, INV-REQ-422, INV-REQ-505, INV-REQ-1284; owner answers
 * INV-Q-10113 to INV-Q-10121, INV-Q-10288.
 */
@Suppress("LargeClass", "LongMethod", "TooManyFunctions") // one scenario per probe of the task
class ElectionChangeLifecycleHttpTest {
    private fun ChangeLab.firstRate(entity: EntityResponse) {
        asCaller(ADMIN)
        assertThat(rate(entity, "MAS", DAILY, TODAY).status).isEqualTo(201)
    }

    /** A pending rate change requested by U1. */
    private fun ChangeLab.requested(
        entity: EntityResponse,
        source: String = "BNM",
        basis: RateSourceElectionRequest.Basis = MONTHLY,
    ): PendingElectionChange {
        asCaller(ADMIN)
        return pendingOf(rate(entity, source, basis))
    }

    private fun ChangeLab.states(entity: EntityResponse) = e.changeStates(entity)

    @Test
    fun `INV-AC-13527 a pending change is closed only by approval, withdrawal or decline and never expires`() {
        changeLab { l ->
            l.entity { _, entity ->
                l.asCaller(ADMIN)
                assertThat(l.rate(entity, "MAS", DAILY, TODAY).status).isEqualTo(201)
                assertThat(l.elect(entity, "INVOICE_DATE", TODAY).status).isEqualTo(201)
                val b = l.pendingOf(l.rate(entity, "BNM", MONTHLY))
                // a second change of the same entity and variation point is refused 409 and stores nothing
                val second = l.rate(entity, "ECB", DAILY)
                assertThat(second.status to second.code).isEqualTo(409 to "TNC-013")
                assertThat(l.states(entity)).containsExactly("PENDING")
                // U1 withdraws B; the stored election still equals A and one audit entry names U1
                val withdrawn = l.withdraw(entity, b.changeRef)
                assertThat(withdrawn.status).isEqualTo(200)
                assertThat(l.states(entity)).containsExactly("WITHDRAWN")
                assertThat(
                    l.e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE).map { it.value },
                ).containsExactly("MAS")
                assertThat(l.e.changeAudits(entity, "election-change.withdrawn")).isEqualTo(1)
                assertThat(
                    l.auditTexts(entity).single { it.contains("election-change.withdrawn") },
                ).contains(ADMIN)
                val c = l.pendingOf(l.rate(entity, "ECB", DAILY))
                // list 1 holds exactly C
                l.asCaller(APPROVER)
                val list1 = l.list(entity)
                assertThat(list1.status).isEqualTo(200)
                assertThat(refsOf(list1)).containsExactly(c.changeRef)
                // D: another variation point, effective date yesterday, still accepted 202
                l.asCaller(ADMIN)
                val d = l.pendingOf(l.elect(entity, "POSTING_DATE", TODAY.minusDays(1)))
                l.asCaller(APPROVER)
                assertThat(refsOf(l.list(entity))).containsExactlyInAnyOrder(c.changeRef, d.changeRef)
                // the clock moves 400 days: nothing expires
                l.clock.now = l.clock.now.plus(Duration.ofDays(400))
                assertThat(refsOf(l.list(entity))).containsExactlyInAnyOrder(c.changeRef, d.changeRef)
                // U2 declines C (one audit entry naming U2) and the approval of D is refused 422 POL-014
                val declined = l.decline(entity, c.changeRef)
                assertThat(declined.status).isEqualTo(200)
                assertThat(l.e.changeAudits(entity, "election-change.declined")).isEqualTo(1)
                assertThat(
                    l.auditTexts(entity).single { it.contains("election-change.declined") },
                ).contains(APPROVER)
                val approval = l.approve(entity, d.changeRef)
                assertThat(approval.status to approval.code).isEqualTo(422 to "POL-014")
                assertThat(l.states(entity)).containsExactly("WITHDRAWN", "DECLINED", "PENDING")
                assertThat(refsOf(l.list(entity))).containsExactly(d.changeRef)
                // the stored election still equals A and no audit entry stores a role
                assertThat(
                    l.e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE).map { it.value },
                ).containsExactly("MAS")
                val roles =
                    listOf("entity.admin", "tenant.admin", "tax.approver") +
                        TestOperationRoles.holders(ElectionPermissions.WITHDRAW) +
                        TestOperationRoles.holders(ElectionPermissions.LIST)
                l.auditTexts(entity).forEach { text -> roles.forEach { assertThat(text).doesNotContain(it) } }
            }
        }
    }

    private fun refsOf(reply: Reply): List<String> =
        (reply.body as com.dynokore.gst.tenancy.contract.model.PendingElectionChangeList).items.map {
            it.changeRef
        }

    /** The refusal [who] gets withdrawing [ref], and that the change is PENDING and unaudited afterwards. */
    private fun ChangeLab.withdrawRefused(
        entity: EntityResponse,
        ref: String,
        who: String,
        status: Int = 403,
        code: String = "SRT-004",
    ) {
        asCaller(who)
        val before = e.changeAudits(entity, "election-change.")
        val reply = withdraw(entity, ref)
        assertThat(reply.status to reply.code).isEqualTo(status to code)
        assertThat(states(entity)).containsExactly("PENDING")
        assertThat(e.changeAudits(entity, "election-change.")).isEqualTo(before)
    }

    @Test
    fun `INV-AC-19490 the approver who is not the requester withdrawing is 403 SRT-004 and the change stays PENDING`() {
        changeLab { l ->
            l.entity { _, entity ->
                l.firstRate(entity)
                val change = l.requested(entity)
                l.withdrawRefused(entity, change.changeRef, APPROVER)
            }
        }
    }

    @Test
    fun `INV-AC-19490 the caller identity USR-004 with a trailing space withdrawing is 403 and the exact caller may`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                l.people.grant(OTHER_ADMIN, entity.entityRef, "entity.admin")
                l.people.grantRole(OTHER_ADMIN, entity.entityRef, l.withdrawRole(), site = GrantSite(tenant))
                l.asCaller(OTHER_ADMIN)
                assertThat(l.rate(entity, "MAS", DAILY, TODAY).status).isEqualTo(201)
                val change = l.pendingOf(l.rate(entity, "BNM", MONTHLY))
                assertThat(change.requestedBy).isEqualTo("identity:USR-004")
                l.withdrawRefused(entity, change.changeRef, "identity:USR-004 ")
                l.asCaller("identity:USR-004")
                assertThat(l.withdraw(entity, change.changeRef).status).isEqualTo(200)
            }
        }
    }

    @Test
    fun `INV-AC-19490 a person who holds approve but not withdraw cannot withdraw own change, 403`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                val approveOnly = "identity:USR-031"
                // the catalogue gives tax.approver both keys, so the matrix of this replica drops withdraw from it:
                // the check is by permission key, never by role name (INV-Q-10027)
                val narrowed =
                    OperationRoleMap { key ->
                        TestOperationRoles.map.rolesFor(key).filterKeys {
                            !(key == ElectionPermissions.WITHDRAW && it == "tax.approver")
                        }
                    }
                val replica = AuthorityReplica(l.clock, narrowed).also { it.onSnapshotComplete(0) }
                replica.accept(
                    AuthorityChanged(
                        tenant,
                        approveOnly,
                        1,
                        AuthorityRecord.RoleGrantRecord(
                            "GR-AO",
                            RoleGrantState.ACTIVE,
                            RoleGrant(
                                "tax.approver",
                                GrantScope.ENTITY,
                                entity.entityRef,
                                l.clock.now.minusSeconds(60),
                            ),
                        ),
                    ),
                )
                replica.observeHead(1)
                val authority = ReplicaActionAuthority(replica, l.tenantContext(), l.clock)
                assertThat(authority.holds(approveOnly, entity.entityRef, ElectionPermissions.APPROVE)).isTrue()
                assertThat(authority.holds(approveOnly, entity.entityRef, ElectionPermissions.WITHDRAW)).isFalse()
                l.people.grant(approveOnly, entity.entityRef, "entity.admin")
                l.asCaller(approveOnly)
                assertThat(l.rate(entity, "MAS", DAILY, TODAY).status).isEqualTo(201)
                val change = l.pendingOf(l.rate(entity, "BNM", MONTHLY))
                l.auth.replicaAuth = authority
                l.withdrawRefused(entity, change.changeRef, approveOnly)
            }
        }
    }

    @Test
    fun `INV-AC-19490 a withdraw grant on another entity is 403 on this one`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                val elsewhere = "identity:USR-032"
                l.people.grant(elsewhere, entity.entityRef, "entity.admin")
                l.people.grantRole(elsewhere, "ENT-OTHER", l.withdrawRole(), site = GrantSite(tenant))
                l.asCaller(elsewhere)
                assertThat(l.rate(entity, "MAS", DAILY, TODAY).status).isEqualTo(201)
                val change = l.pendingOf(l.rate(entity, "BNM", MONTHLY))
                l.withdrawRefused(entity, change.changeRef, elsewhere)
            }
        }
    }

    @Test
    fun `INV-AC-19490 an expired withdraw grant is 403`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                val expired = "identity:USR-033"
                l.people.grant(expired, entity.entityRef, "entity.admin")
                l.people.grantRole(
                    expired,
                    entity.entityRef,
                    l.withdrawRole(),
                    validUntil = Instant.now().minusSeconds(1),
                    site = GrantSite(tenant),
                )
                l.asCaller(expired)
                assertThat(l.rate(entity, "MAS", DAILY, TODAY).status).isEqualTo(201)
                val change = l.pendingOf(l.rate(entity, "BNM", MONTHLY))
                l.withdrawRefused(entity, change.changeRef, expired)
            }
        }
    }

    @Test
    fun `INV-AC-19490 with no replica bound the withdraw is 403 and the change stays PENDING`() {
        changeLab { l ->
            l.entity { _, entity ->
                l.firstRate(entity)
                val change = l.requested(entity)
                l.auth.replicaAuth = ReplicaActionAuthority(null, l.tenantContext(), l.clock)
                l.withdrawRefused(entity, change.changeRef, ADMIN)
                l.auth.replicaAuth = l.live()
                assertThat(l.withdraw(entity, change.changeRef).status).isEqualTo(200)
            }
        }
    }

    @Test
    fun `INV-AC-19490 a replica lagging beyond the bound refuses the withdraw 403`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                l.firstRate(entity)
                val change = l.requested(entity)
                val lagging =
                    AuthorityReplica(l.clock, TestOperationRoles.map, lagBound = Duration.ofSeconds(1)).also {
                        it.onSnapshotComplete(0)
                    }
                lagging.accept(
                    AuthorityChanged(
                        tenant,
                        ADMIN,
                        1,
                        AuthorityRecord.RoleGrantRecord(
                            "GR-LAG",
                            RoleGrantState.ACTIVE,
                            RoleGrant(
                                l.withdrawRole(),
                                GrantScope.ENTITY,
                                entity.entityRef,
                                l.clock.now.minusSeconds(60),
                            ),
                        ),
                    ),
                )
                val authority = ReplicaActionAuthority(lagging, l.tenantContext(), l.clock)
                l.auth.replicaAuth = authority
                assertThat(authority.holds(ADMIN, entity.entityRef, ElectionPermissions.WITHDRAW)).isTrue()
                lagging.observeHead(5)
                l.clock.now = l.clock.now.plusSeconds(30)
                l.withdrawRefused(entity, change.changeRef, ADMIN)
            }
        }
    }

    @Test
    fun `INV-AC-19490 the action election-change withdraw with a trailing space is not held`() {
        changeLab { l ->
            l.entity { _, entity ->
                l.firstRate(entity)
                val change = l.requested(entity)
                val key = ElectionPermissions.WITHDRAW
                assertThat(l.auth.holds(ADMIN, entity.entityRef, key)).isTrue()
                assertThat(l.auth.replicaAuth!!.holds(ADMIN, entity.entityRef, "$key ")).isFalse()
                assertThat(l.auth.replicaAuth!!.holds("$ADMIN ", entity.entityRef, key)).isFalse()
                l.asCaller(ADMIN)
                assertThat(l.withdraw(entity, change.changeRef).status).isEqualTo(200)
            }
        }
    }

    @Test
    fun `INV-AC-19490 a change of another tenant is 404 SRT-005 and stays PENDING`() {
        changeLab { l ->
            l.entity { _, entity ->
                l.firstRate(entity)
                val change = l.requested(entity)
                l.env.withTenant { other ->
                    val foreign = l.env.entity(other, l.env.group(other))
                    l.e.usualPeople(foreign)
                    l.auth.replicaAuth = l.live()
                    l.grantUsual(other, foreign)
                    l.asCaller(ADMIN)
                    val viaForeign = l.withdraw(foreign, change.changeRef)
                    assertThat(viaForeign.status to viaForeign.code).isEqualTo(404 to "SRT-005")
                    val viaOwn = l.withdraw(entity, change.changeRef)
                    assertThat(viaOwn.status to viaOwn.code).isEqualTo(404 to "SRT-005")
                }
                assertThat(l.states(entity)).containsExactly("PENDING")
            }
        }
    }

    @Test
    fun `INV-AC-19490 a replay of the withdraw is 404 SRT-005 and one audit entry`() {
        changeLab { l ->
            l.entity { _, entity ->
                l.firstRate(entity)
                val change = l.requested(entity)
                assertThat(l.withdraw(entity, change.changeRef).status).isEqualTo(200)
                val replay = l.withdraw(entity, change.changeRef)
                assertThat(replay.status to replay.code).isEqualTo(404 to "SRT-005")
                assertThat(l.e.changeAudits(entity, "election-change.withdrawn")).isEqualTo(1)
                assertThat(l.states(entity)).containsExactly("WITHDRAWN")
            }
        }
    }

    @Test
    fun `INV-AC-19490 the requester declining own change is 403 and the change stays PENDING`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                l.people.grantRole(ADMIN, entity.entityRef, "tax.approver", site = GrantSite(tenant))
                l.firstRate(entity)
                val change = l.requested(entity)
                val own = l.decline(entity, change.changeRef)
                assertThat(own.status to own.code).isEqualTo(403 to "SRT-004")
                assertThat(l.states(entity)).containsExactly("PENDING")
                assertThat(l.e.changeAudits(entity, "election-change.declined")).isZero()
            }
        }
    }

    @Test
    fun `INV-AC-19490 the list holds one change of each state only as PENDING and none of another entity or tenant`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                l.firstRate(entity)
                l.asCaller(ADMIN)
                assertThat(l.elect(entity, "INVOICE_DATE", TODAY).status).isEqualTo(201)
                assertThat(l.elect(entity, "CLAIMABLE_PORTION", TODAY).status).isEqualTo(201)
                // APPROVED: a VP-22 change approved by U2; WITHDRAWN: next change withdrawn; DECLINED: next declined
                val approved = l.pendingOf(l.elect(entity, "POSTING_DATE", LATER))
                l.asCaller(APPROVER)
                assertThat(l.approve(entity, approved.changeRef).status).isEqualTo(200)
                l.asCaller(ADMIN)
                val withdrawn = l.pendingOf(l.elect(entity, "INVOICE_DATE", LATER.plusDays(1)))
                assertThat(l.withdraw(entity, withdrawn.changeRef).status).isEqualTo(200)
                val declined = l.pendingOf(l.elect(entity, "INVOICE_DATE", LATER.plusDays(2)))
                l.asCaller(APPROVER)
                assertThat(l.decline(entity, declined.changeRef).status).isEqualTo(200)
                l.asCaller(ADMIN)
                val pendingBasis = l.pendingOf(l.elect(entity, "INVOICE_DATE", LATER.plusDays(3)))
                val pendingMode = l.pendingOf(l.elect(entity, "ENTIRE_INVOICE", LATER))
                val pendingRate = l.pendingOf(l.rate(entity, "BNM", MONTHLY))
                assertThat(l.states(entity)).containsExactlyInAnyOrder(
                    "APPROVED",
                    "WITHDRAWN",
                    "DECLINED",
                    "PENDING",
                    "PENDING",
                    "PENDING",
                )
                // a sibling entity of the tenant and an entity of another tenant, each with a pending change
                val sibling = l.env.entity(tenant, l.env.group(tenant))
                l.e.usualPeople(sibling)
                l.grantUsual(tenant, sibling)
                l.asCaller(ADMIN)
                l.e.staged(tenant, "SCOPE-SG", "2.0", sources = THREE_SOURCES)
                l.e.activated(tenant, "SCOPE-SG", "2.0", TODAY.minusDays(20))
                assertThat(l.rate(sibling, "MAS", DAILY, TODAY).status).isEqualTo(201)
                val siblingChange = l.pendingOf(l.rate(sibling, "BNM", MONTHLY))
                var foreignRef = ""
                l.env.withTenant { other ->
                    val foreign = l.env.entity(other, l.env.group(other))
                    l.e.usualPeople(foreign)
                    l.e.staged(other, "SCOPE-SG", "1.0", sources = THREE_SOURCES)
                    l.e.activated(other, "SCOPE-SG", "1.0", TODAY.minusDays(30))
                    l.grantUsual(other, foreign)
                    l.asCaller(ADMIN)
                    assertThat(l.rate(foreign, "MAS", DAILY, TODAY).status).isEqualTo(201)
                    foreignRef = l.pendingOf(l.rate(foreign, "BNM", MONTHLY)).changeRef
                }
                l.asCaller(APPROVER)
                val all = refsOf(l.list(entity))
                assertThat(
                    all,
                ).containsExactlyInAnyOrder(pendingBasis.changeRef, pendingMode.changeRef, pendingRate.changeRef)
                assertThat(all).doesNotContain(
                    approved.changeRef,
                    withdrawn.changeRef,
                    declined.changeRef,
                    siblingChange.changeRef,
                    foreignRef,
                )
                // a page of limit items with the next-page token, then the rest
                val first = l.list(entity, limit = 2)
                val firstPage = first.body as com.dynokore.gst.tenancy.contract.model.PendingElectionChangeList
                assertThat(firstPage.items).hasSize(2)
                assertThat(firstPage.nextCursor).isNotBlank()
                val second = l.list(entity, limit = 2, cursor = firstPage.nextCursor)
                val secondPage = second.body as com.dynokore.gst.tenancy.contract.model.PendingElectionChangeList
                assertThat(secondPage.items).hasSize(1)
                assertThat(secondPage.nextCursor).isNull()
                assertThat(firstPage.items.map { it.changeRef } + secondPage.items.map { it.changeRef })
                    .containsExactlyInAnyOrderElementsOf(all)
            }
        }
    }

    @Test
    fun `INV-AC-19490 a list without the list grant is 403`() {
        changeLab { l ->
            l.entity { _, entity ->
                l.firstRate(entity)
                l.requested(entity)
                l.asCaller(NOBODY)
                val reply = l.list(entity)
                assertThat(reply.status to reply.code).isEqualTo(403 to "SRT-004")
            }
        }
    }

    /** A VP-22 change effective [from]; the clock reads [clockDay] at its approval; returns the reply and states. */
    private fun approvalOn(
        from: LocalDate,
        clockDay: LocalDate,
    ): Pair<Reply, List<String>> {
        var out: Pair<Reply, List<String>>? = null
        changeLab { l ->
            l.entity { _, entity ->
                l.asCaller(ADMIN)
                assertThat(l.elect(entity, "INVOICE_DATE", TODAY).status).isEqualTo(201)
                val change = l.pendingOf(l.elect(entity, "POSTING_DATE", from))
                l.at(clockDay)
                l.asCaller(APPROVER)
                out = l.approve(entity, change.changeRef) to l.states(entity)
            }
        }
        return checkNotNull(out)
    }

    @Test
    fun `INV-AC-19490 the approval of effectiveFrom yesterday is 422 POL-014 and the change stays PENDING`() {
        val from = TODAY.plusDays(1)
        val (reply, states) = approvalOn(from, from.plusDays(1))
        assertThat(reply.status to reply.code).isEqualTo(422 to "POL-014")
        assertThat(states).containsExactly("PENDING")
    }

    @Test
    fun `INV-AC-19490 the approval of effectiveFrom today is accepted`() {
        val from = TODAY.plusDays(1)
        val (reply, states) = approvalOn(from, from)
        assertThat(reply.status).isEqualTo(200)
        assertThat(states).containsExactly("APPROVED")
    }

    @Test
    fun `INV-AC-19490 the approval of effectiveFrom tomorrow is accepted`() {
        val from = TODAY.plusDays(1)
        val (reply, states) = approvalOn(from, from.minusDays(1))
        assertThat(reply.status).isEqualTo(200)
        assertThat(states).containsExactly("APPROVED")
    }

    @Test
    fun `INV-AC-19490 two threads changing at once give one 202 and one 409 with code TNC-013`() {
        changeLab { l ->
            l.entity { tenant, entity ->
                l.firstRate(entity)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val start = CountDownLatch(1)
                    val calls =
                        listOf("BNM" to MONTHLY, "ECB" to DAILY).map { (source, basis) ->
                            pool.submit<Reply> {
                                l.env.inTenant(tenant) {
                                    start.await()
                                    l.rate(entity, source, basis)
                                }
                            }
                        }
                    start.countDown()
                    val replies = calls.map { it.get(60, TimeUnit.SECONDS) }
                    assertThat(replies.map { it.status }).containsExactlyInAnyOrder(202, 409)
                    assertThat(replies.single { it.status == 409 }.code).isEqualTo("TNC-013")
                    assertThat(l.states(entity)).containsExactly("PENDING")
                    assertThat(l.e.changeAudits(entity, "election-change.requested")).isEqualTo(1)
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    /** The second call while PENDING: 409 TNC-013, nothing stored, nothing audited; after a withdraw it is 202. */
    private fun secondWhilePending(
        first: (ChangeLab, EntityResponse) -> Reply,
        again: (ChangeLab, EntityResponse) -> Reply,
    ) {
        changeLab { l ->
            l.entity { _, entity ->
                l.firstRate(entity)
                l.asCaller(ADMIN)
                assertThat(l.elect(entity, "INVOICE_DATE", TODAY).status).isEqualTo(201)
                val pending = l.pendingOf(first(l, entity))
                val before = l.states(entity)
                val audits = l.e.changeAudits(entity, "election-change.")
                val refused = again(l, entity)
                assertThat(refused.status).isEqualTo(409)
                assertThat(refused.code).isEqualTo("TNC-013")
                assertThat(refused.code).isNotEqualTo("SRT-008")
                assertThat(l.states(entity)).isEqualTo(before).hasSize(1)
                assertThat(l.e.changeAudits(entity, "election-change.")).isEqualTo(audits)
                assertThat(l.list(entity).let { refsOf(it) }.single()).isEqualTo(pending.changeRef)
                assertThat(l.withdraw(entity, pending.changeRef).status).isEqualTo(200)
                assertThat(again(l, entity).status).isEqualTo(202)
            }
        }
    }

    @Test
    fun `INV-AC-19490 a second recordElection while the first change is PENDING is 409 TNC-013 and stores nothing`() =
        secondWhilePending(
            { l, entity -> l.elect(entity, "POSTING_DATE", LATER) },
            { l, entity -> l.elect(entity, "POSTING_DATE", LATER.plusDays(1)) },
        )

    @Test
    fun `INV-AC-19490 a second electRateSource while the first change is PENDING is 409 TNC-013 and stores nothing`() =
        secondWhilePending(
            { l, entity -> l.rate(entity, "BNM", MONTHLY) },
            { l, entity -> l.rate(entity, "ECB", DAILY) },
        )
}
