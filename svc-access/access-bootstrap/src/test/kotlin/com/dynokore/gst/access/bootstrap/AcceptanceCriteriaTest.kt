package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision.ALLOW
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision.DENY
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PeriodRange
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * The acceptance criteria of INV-T-4934 at the service boundary: the real application context over a real PostgreSQL
 * with
 * forced row-level security and the signed binding, the controllers called under a bound tenant, the structure and
 * authority replicas fed the way the platform feeds them. Test names carry the criterion key (acKeyCoverage).
 *
 * The permission names and rows are test data (FixtureMatrix): the production catalogue is empty until the owner rules
 * the action catalogue, and the scope grammar of the period and group scopes is not published, so a period grant is
 * built
 * through the repository here. See the merge request, "Gaps".
 */
class AcceptanceCriteriaTest {
    private val tenantA = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("0b000000-0000-4000-8000-00000000000b"))
    private val admin = "user:tenant-admin"

    private fun run(block: (AssertableWebApplicationContext, Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(context, Env(context))
        }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val grantsApi = context.getBean(GrantsController::class.java)
        val authoriseApi = context.getBean(AuthoriseController::class.java)
        val repository = context.getBean(GrantRepository::class.java)
        val clock = context.getBean(Clock::class.java) as MovableClock
        val structure = context.getBean(StructureReplica::class.java)
        val captured = context.getBean(CapturedEvents::class.java)
        val consent = context.getBean(ConsentSwitch::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, admin) { block() }

        fun <T> readIn(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, admin) { tx.inTransaction(block) }

        fun entity(
            tenant: TenantId,
            ref: String,
        ) {
            structure.accept(
                OrganisationStructureChanged(tenant, ref, StructureChange.CREATED, BoundaryFixture.entityNode()),
            )
        }

        fun grant(
            tenant: TenantId,
            identity: String,
            role: String,
            scope: String,
        ) = inTenant(tenant) {
            grantsApi.grantRole(
                UUID.randomUUID(),
                GrantRequest(
                    GrantRequest.GranteeKind.STAFF,
                    identity,
                    role,
                    scope,
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusDays(400),
                ),
            )
        }

        fun decide(
            tenant: TenantId,
            identity: String,
            permission: String,
            entity: String,
        ): AuthoriseResponse =
            inTenant(tenant) { authoriseApi.authorise(identity, permission, "entity:$entity").body!! }
    }

    @Test
    fun `INV-AC-1907 a grant without a scope is refused and a grant with a scope is accepted`() =
        run { _, env ->
            env.entity(tenantA, "ENT-1")
            val refused = runCatching { env.grant(tenantA, "IDN-1", "tax.approver", "") }.exceptionOrNull()
            assertThat(refused).isNotNull().hasMessageContaining("SRT-001")
            assertThat(env.readIn(tenantA) { env.repository.findByIdentity(IdentityRef("IDN-1")) }).isEmpty()

            val accepted = env.grant(tenantA, "IDN-1", "tax.approver", "entity:ENT-1")
            assertThat(accepted.statusCode.value()).isEqualTo(201)
            assertThat(accepted.body!!.role).isEqualTo("tax.approver")
        }

    @Test
    fun `INV-AC-1911 a grant scoped to entity and period gives access only inside that range`() =
        run { _, env ->
            env.entity(tenantA, "ENT-E")
            val q1toQ2 = PeriodRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30))
            val now = env.clock.instant()
            env.inTenant(tenantA) {
                env.tx.inTransaction {
                    env.repository.lockIdentity(IdentityRef("IDN-AUD"), GranteeKind.EXTERNAL)
                    env.repository.insert(
                        Grant.issue(
                            GrantHeader(GrantRef("GRT-PERIOD"), TenantRef(tenantA.toString()), IdentityRef("IDN-AUD")),
                            Role.AUDITOR,
                            Scope.EntityPeriod(EntityRef("ENT-E"), q1toQ2),
                            ValidityWindow(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30))),
                        ),
                    )
                }
            }
            val authorise = env.context.getBean(Authorise::class.java)

            fun ask(period: PeriodRange?) =
                env.inTenant(tenantA) {
                    authorise.handle(IdentityRef("IDN-AUD"), FixtureMatrix.VIEW, Target(EntityRef("ENT-E"), period))
                }
            val q2 = PeriodRange(LocalDate.of(2027, 4, 1), LocalDate.of(2027, 6, 30))
            val q3 = PeriodRange(LocalDate.of(2027, 7, 1), LocalDate.of(2027, 9, 30))
            assertThat(ask(q2)).isInstanceOf(Decision.Allow::class.java)
            assertThat(ask(q3)).isInstanceOf(Decision.Deny::class.java)
            assertThat(ask(null)).isInstanceOf(Decision.Deny::class.java)

            env.clock.advance(Duration.ofDays(31)) // after the validity window every action is refused
            assertThat(ask(q2)).isInstanceOf(Decision.Deny::class.java)
        }

    @Test
    fun `INV-AC-2544 one identity holding grants on two entities acts under each grant within its scope only`() =
        run { _, env ->
            env.entity(tenantA, "ENT-A")
            env.entity(tenantA, "ENT-B")
            env.grant(tenantA, "IDN-2", "tax.approver", "entity:ENT-A")
            env.grant(tenantA, "IDN-2", "entity.admin", "entity:ENT-B")
            assertThat(env.decide(tenantA, "IDN-2", "pack.approve", "ENT-A").decision).isEqualTo(ALLOW)
            assertThat(env.decide(tenantA, "IDN-2", "profile.edit", "ENT-B").decision).isEqualTo(ALLOW)
            assertThat(env.decide(tenantA, "IDN-2", "pack.approve", "ENT-B").decision).isEqualTo(DENY)
            assertThat(env.decide(tenantA, "IDN-2", "profile.edit", "ENT-A").decision).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-1935 a cross-tenant identity sees one tenant at a time`() =
        run { _, env ->
            env.entity(tenantA, "ENT-T1")
            env.entity(tenantB, "ENT-T2")
            env.grant(tenantA, "IDN-ADV", "auditor", "entity:ENT-T1")
            env.grant(tenantB, "IDN-ADV", "auditor", "entity:ENT-T2")
            // acting in tenant A: only tenant A's grant is visible, and it covers its own entity only
            assertThat(env.decide(tenantA, "IDN-ADV", "doc.view", "ENT-T1").decision).isEqualTo(ALLOW)
            assertThat(env.decide(tenantA, "IDN-ADV", "doc.view", "ENT-T2").decision).isEqualTo(DENY)
            assertThat(env.readIn(tenantA) { env.repository.findByIdentity(IdentityRef("IDN-ADV")) }).hasSize(1)
            // after the switch to tenant B: only tenant B's data
            assertThat(env.decide(tenantB, "IDN-ADV", "doc.view", "ENT-T2").decision).isEqualTo(ALLOW)
            assertThat(env.decide(tenantB, "IDN-ADV", "doc.view", "ENT-T1").decision).isEqualTo(DENY)
            assertThat(env.readIn(tenantB) { env.repository.findByIdentity(IdentityRef("IDN-ADV")) }).hasSize(1)
        }

    @Test
    fun `INV-AC-1910 an auditor cannot resolve an exception or annotate but can view and export`() =
        run { _, env ->
            env.entity(tenantA, "ENT-X")
            env.grant(tenantA, "IDN-AUDITOR", "auditor", "entity:ENT-X")

            fun decision(permission: String) = env.decide(tenantA, "IDN-AUDITOR", permission, "ENT-X").decision
            assertThat(decision("exception.resolve")).isEqualTo(DENY)
            assertThat(decision("doc.annotate")).isEqualTo(DENY)
            assertThat(decision("doc.view")).isEqualTo(ALLOW)
            assertThat(decision("doc.export")).isEqualTo(ALLOW)
        }

    @Test
    fun `INV-AC-925 a deployment operator is refused invoice content but may export the support bundle`() =
        run { _, env ->
            env.entity(tenantA, "ENT-OP")
            env.grant(tenantA, "IDN-OPERATOR", "deployment.operator", "entity:ENT-OP")

            fun decision(permission: String) = env.decide(tenantA, "IDN-OPERATOR", permission, "ENT-OP").decision
            assertThat(decision("doc.view.content")).isEqualTo(DENY)
            assertThat(decision("support.bundle.export")).isEqualTo(ALLOW)
        }

    @Test
    fun `INV-AC-1899 a consent-derived grant reaches only the client whose consent is live`() =
        run { _, env ->
            env.entity(tenantA, "CLIENT-A")
            env.entity(tenantA, "CLIENT-B")
            env.consent.grant(EntityRef("CLIENT-A"))
            env.consent.grant(EntityRef("CLIENT-B"))
            val now = env.clock.instant()
            env.inTenant(tenantA) {
                env.tx.inTransaction {
                    env.repository.lockIdentity(IdentityRef("IDN-AGENT"), GranteeKind.STAFF)
                    env.repository.insert(
                        Grant.issue(
                            GrantHeader(GrantRef("GRT-AGENT"), TenantRef(tenantA.toString()), IdentityRef("IDN-AGENT")),
                            Role.AGENT_OPERATOR,
                            Scope.ConsentDerived(TenantRef(tenantA.toString()), env.consent.check()),
                            ValidityWindow(now.minus(Duration.ofDays(1)), null),
                        ),
                    )
                }
            }
            assertThat(env.decide(tenantA, "IDN-AGENT", "doc.view", "CLIENT-A").decision).isEqualTo(ALLOW)
            env.consent.withdraw(EntityRef("CLIENT-B"))
            assertThat(env.decide(tenantA, "IDN-AGENT", "doc.view", "CLIENT-B").decision).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-2187 an agent action right after the client withdraws consent is denied at the next check`() =
        run { _, env ->
            env.entity(tenantA, "CLIENT-W")
            env.consent.grant(EntityRef("CLIENT-W"))
            val now = env.clock.instant()
            env.inTenant(tenantA) {
                env.tx.inTransaction {
                    env.repository.lockIdentity(IdentityRef("IDN-AGENT2"), GranteeKind.STAFF)
                    env.repository.insert(
                        Grant.issue(
                            GrantHeader(
                                GrantRef("GRT-AGENT2"),
                                TenantRef(tenantA.toString()),
                                IdentityRef("IDN-AGENT2"),
                            ),
                            Role.AGENT_OPERATOR,
                            Scope.ConsentDerived(TenantRef(tenantA.toString()), env.consent.check()),
                            ValidityWindow(now.minus(Duration.ofDays(1)), null),
                        ),
                    )
                }
            }
            assertThat(env.decide(tenantA, "IDN-AGENT2", "doc.view", "CLIENT-W").decision).isEqualTo(ALLOW)
            env.consent.withdraw(EntityRef("CLIENT-W"))
            env.clock.advance(Duration.ofSeconds(1))
            assertThat(env.decide(tenantA, "IDN-AGENT2", "doc.view", "CLIENT-W").decision).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-2186 an authority change reaches the replica within one second, structure comes from cache`() =
        run { _, env ->
            env.entity(tenantA, "ENT-R")
            val replica = AuthorityReplica(env.clock, fallback = null)
            // the start-up sync of a service whose replica is current
            replica.onSnapshotComplete(0)
            val feed = env.context.getBean(AuthorityFeedHandler::class.java)
            val feedRequest =
                AuthorizationRequest(tenantA, Principal("IDN-R", PrincipalKind.HUMAN), "ENT-R", "doc.view")

            fun deliver() {
                val handler = AuthorityFeedHandler(replica)
                env.captured.envelopes
                    .toList()
                    .also { env.captured.envelopes.clear() }
                    .filter { it.type == ProductEventTypes.AUTHORITY_CHANGED }
                    .forEach { handler.handle(it) }
            }
            assertThat(feed).isNotNull()
            env.grant(tenantA, "IDN-R", "auditor", "entity:ENT-R")
            deliver()
            assertThat(replica.decide(feedRequest, Deadline.after(Duration.ofMillis(500), env.clock)).allowed).isTrue()

            val grantRef =
                env.readIn(tenantA) {
                    env.repository
                        .findByIdentity(IdentityRef("IDN-R"))
                        .single()
                        .ref
                }
            val started = System.nanoTime()
            env.inTenant(tenantA) { env.grantsApi.revokeGrant(grantRef.value, UUID.randomUUID()) }
            deliver()
            val decision = replica.decide(feedRequest, Deadline.after(Duration.ofMillis(500), env.clock))
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertThat(decision.allowed).isFalse()
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1))
            // the replica decided locally: no access-svc call (no fallback is configured and none was needed)
            assertThat(decision.source).isEqualTo(DecisionSource.LOCAL)
        }

    @Test
    fun `INV-AC-1909 every grant change writes an audit entry with actor, scope and prior state`() =
        run { _, env ->
            env.entity(tenantA, "ENT-AU")
            val response = env.grant(tenantA, "IDN-AUDIT", "tax.approver", "entity:ENT-AU")
            env.inTenant(tenantA) { env.grantsApi.revokeGrant(response.body!!.grantRef, UUID.randomUUID()) }
            val rows =
                BoundaryFixture.asSuperuser { c ->
                    c.createStatement().executeQuery(AUDIT_ROWS).use { rs ->
                        generateSequence { if (rs.next()) rs.getString(2) else null }.toList()
                    }
                }
            val own = rows.filter { "ENT-AU" in it }
            assertThat(own).hasSize(2)
            assertThat(own[0]).contains("grantRole").contains(admin)
            assertThat(own[1]).contains("revokeGrant").contains("priorState").contains("ACTIVE")
        }

    @Test
    fun `row-level security hides another tenant's rows and every row from an unbound runtime session`() =
        run { _, env ->
            env.entity(tenantA, "ENT-S1")
            env.grant(tenantA, "IDN-S", "auditor", "entity:ENT-S1")
            assertThat(env.readIn(tenantB) { env.repository.findByIdentity(IdentityRef("IDN-S")) }).isEmpty()
            val unbound =
                BoundaryFixture.asRuntime { c ->
                    c.createStatement().executeQuery("select count(*) from access.role_grant").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            assertThat(unbound).isZero()
        }

    private companion object {
        const val AUDIT_ROWS =
            "select type, data::text from access.outbox where type like '%AuditEntryRecorded%' order by seq"
    }
}
