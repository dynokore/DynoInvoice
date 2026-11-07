package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.adapters.http.IdentityTriplesController
import com.dynokore.gst.masterdata.application.identity.ConfirmerPermission
import com.dynokore.gst.masterdata.application.identity.ProposeCommand
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-T-8118: the confirmation of an identity triple change is decided by the permission `entity-config.confirm`
 * through the production `ConfirmerPermission` bean over a real [AuthorityReplica] (INV-Q-10027, INV-Q-10025), and the
 * stored confirmation records the user ID, never a role (INV-Q-10647).
 */
class ConfirmerPermissionTest {
    private val tenantA = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("0b000000-0000-4000-8000-00000000000b"))
    private val proposer = "IDN-PROPOSER"
    private val agent = Constitution("SECTION_33_2_AGENT")
    private val confirmerKey = "entity-config.confirm"

    /** A replica of its own with the production context otherwise as the other tests build it (no resolver bean). */
    private inner class Fixture {
        val clock = MovableClock()
        val replica: AuthorityReplica = TestAuthority.newReplica(clock)
        private var version = 0L

        fun grant(
            person: String,
            role: String,
            entity: String,
            tenant: TenantId = tenantA,
            validUntil: Instant? = null,
            ref: String = "g-$person-$role-$entity",
        ): Long {
            val v = ++version
            replica.accept(
                AuthorityChanged(
                    tenant,
                    person,
                    v,
                    AuthorityRecord.RoleGrantRecord(
                        ref,
                        RoleGrantState.ACTIVE,
                        RoleGrant(role, GrantScope.ENTITY, entity, Instant.now().minusSeconds(3600), validUntil),
                    ),
                ),
            )
            replica.observeHead(v)
            return v
        }

        fun remove(
            person: String,
            role: String,
            entity: String,
        ) {
            val v = ++version
            replica.accept(
                AuthorityChanged(
                    tenantA,
                    person,
                    v,
                    AuthorityRecord.RoleGrantRecord(
                        "g-$person-$role-$entity",
                        RoleGrantState.REMOVED,
                        RoleGrant(role, GrantScope.ENTITY, entity, Instant.now().minusSeconds(3600)),
                    ),
                ),
            )
            replica.observeHead(v)
        }

        fun void(person: String) {
            val v = ++version
            val record = AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)
            replica.accept(AuthorityChanged(tenantA, person, v, record))
            replica.observeHead(v)
        }

        fun staleHead() {
            replica.observeHead(version + 1_000)
            clock.advance(Duration.ofSeconds(30))
        }
    }

    private fun run(
        replica: AuthorityReplica?,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture
            .runner(null)
            .let { if (replica != null) it.withBean(AuthorityReplica::class.java, { replica }) else it }
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        private val tenants = context.getBean(TenantContext::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = tenants.bind(tenant, actor) { block() }

        fun proposed(entity: String) =
            inTenant(tenantA, proposer) {
                context.getBean(ProposeIdentityTripleChange::class.java).handle(
                    ProposeCommand(
                        EntityRef(entity),
                        agent,
                        IdentityTriple("0195:U-A", "U-A", "G-A"),
                        LocalDate.of(2026, 1, 1),
                        PersonRef(proposer),
                    ),
                )
            }

        fun confirmedAs(
            actor: String,
            entity: String,
            changeRef: String,
            tenant: TenantId = tenantA,
        ): Any =
            inTenant(tenant, actor) {
                try {
                    context
                        .getBean(IdentityTriplesController::class.java)
                        .confirmIdentityTripleChange(entity, changeRef, UUID.randomUUID())
                        .statusCode
                } catch (refusal: ApiRefusal) {
                    refusal.code
                }
            }

        fun count(
            table: String,
            entity: String,
            where: String,
        ): Int =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery("select count(*) from masterdata.$table where entity_ref = '$entity' and $where")
                    .use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }

        fun pending(entity: String) = count("identity_triple_change", entity, "state = 'PENDING_CONFIRMATION'")

        fun stored(entity: String) = count("identity_triple", entity, "true")

        /** (confirmed_by, confirmed_role) of every row of the table for the entity, in the order of the rows. */
        fun confirmations(
            table: String,
            entity: String,
        ): List<Pair<String?, String?>> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery(
                        "select confirmed_by, confirmed_role from masterdata.$table where entity_ref = '$entity' " +
                            "and confirmed_by is not null order by created_at, confirmed_by",
                    ).use { rs ->
                        buildList {
                            while (rs.next()) add(rs.getString(1) to rs.getString(2))
                        }
                    }
            }
    }

    private fun refused(
        fixture: Fixture?,
        entity: String,
        confirmer: String = "IDN-C",
        before: (Fixture, Env) -> Unit = { _, _ -> },
        tenant: TenantId = tenantA,
    ): Pair<Any, Int> {
        var outcome: Any = ""
        var pending = -1
        run(fixture?.replica) { env ->
            val change = env.proposed(entity)
            if (fixture != null) before(fixture, env)
            outcome = env.confirmedAs(confirmer, entity, change.changeRef, tenant)
            pending = env.pending(entity)
        }
        return outcome to pending
    }

    @Test
    fun `INV-AC-7822 the proposer is refused first whatever it holds, then a different tax approver confirms`() {
        val f = Fixture()
        f.grant("IDN-SOLE", "entity.admin", "ENT-7822")
        f.grant("IDN-SOLE", "tax.approver", "ENT-7822")
        f.grant("IDN-ADVISOR", "tax.approver", "ENT-7822")
        run(f.replica) { env ->
            val change =
                env.inTenant(tenantA, "IDN-SOLE") {
                    env.context.getBean(ProposeIdentityTripleChange::class.java).handle(
                        ProposeCommand(
                            EntityRef("ENT-7822"),
                            agent,
                            IdentityTriple("0195:U-A", "U-A", "G-A"),
                            LocalDate.of(2026, 1, 1),
                            PersonRef("IDN-SOLE"),
                        ),
                    )
                }
            listOf("IDN-SOLE", " IDN-SOLE ", "idn-sole").forEach {
                assertThat(env.confirmedAs(it, "ENT-7822", change.changeRef))
                    .describedAs(it)
                    .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            }
            assertThat(env.pending("ENT-7822")).isEqualTo(1)
            assertThat(env.stored("ENT-7822")).isZero()
            assertThat(env.confirmedAs("IDN-ADVISOR", "ENT-7822", change.changeRef)).isEqualTo(HttpStatus.OK)
            assertThat(env.pending("ENT-7822")).isZero()
        }
    }

    @Test
    fun `exactly one ConfirmerPermission bean exists and it is the replica binding`() {
        val f = Fixture()
        run(f.replica) { env ->
            assertThat(env.context.getBeansOfType(ConfirmerPermission::class.java)).hasSize(1)
            assertThat(
                env.context
                    .getBean(ConfirmerPermission::class.java)
                    .javaClass.simpleName,
            ).isEqualTo("ReplicaConfirmerPermission")
        }
    }

    @Test
    fun `each of the three confirmer roles held on the entity by a different person confirms`() {
        listOf("entity.admin", "tenant.admin", "tax.approver").forEachIndexed { i, role ->
            val f = Fixture()
            val entity = "ENT-ROLE-$i"
            f.grant("IDN-C", role, entity)
            val (outcome, pending) = refused(f, entity)
            assertThat(outcome).describedAs(role).isEqualTo(HttpStatus.OK)
            assertThat(pending).describedAs(role).isZero()
        }
    }

    @Test
    fun `a subject without the permission on this entity now gets 403`() {
        val cases =
            mapOf<String, (Fixture, String) -> Unit>(
                "agent.operator only" to { f, e -> f.grant("IDN-C", "agent.operator", e) },
                "entity.admin on E1 only" to { f, _ -> f.grant("IDN-C", "entity.admin", "ENT-OTHER") },
                "grant in another tenant" to { f, e -> f.grant("IDN-C", "entity.admin", e, tenantB) },
                "expired grant" to { f, e ->
                    f.grant("IDN-C", "entity.admin", e, validUntil = Instant.now().minusSeconds(5))
                },
                "removed grant" to { f, e ->
                    f.grant("IDN-C", "entity.admin", e)
                    f.remove("IDN-C", "entity.admin", e)
                },
                "voided grant" to { f, e ->
                    f.grant("IDN-C", "entity.admin", e)
                    f.void("IDN-C")
                },
                "look-alike role entity.admin2" to { f, e -> f.grant("IDN-C", "entity.admin2", e) },
                "look-alike role Entity.Admin" to { f, e -> f.grant("IDN-C", "Entity.Admin", e) },
                "no grant at all" to { _, _ -> },
            )
        cases.entries.forEachIndexed { i, (name, arrange) ->
            val f = Fixture()
            val entity = "ENT-DENY-$i"
            arrange(f, entity)
            val (outcome, pending) = refused(f, entity)
            assertThat(outcome).describedAs(name).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(pending).describedAs(name).isEqualTo(1)
        }
    }

    @Test
    fun `a stale replica, one with no snapshot and no replica at all give 403 even for a holder`() {
        val stale = Fixture().also { it.grant("IDN-C", "entity.admin", "ENT-STALE") }
        val cold = Fixture()
        val coldReplica = AuthorityReplica(cold.clock, TestAuthority.roleMap)
        val staleOutcome =
            refused(stale, "ENT-STALE", before = { f, _ -> f.staleHead() })
        assertThat(staleOutcome.first).describedAs("stale").isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(staleOutcome.second).isEqualTo(1)
        var coldOutcome: Any = ""
        run(coldReplica) { env ->
            val change = env.proposed("ENT-COLD")
            coldOutcome = env.confirmedAs("IDN-C", "ENT-COLD", change.changeRef)
            assertThat(env.pending("ENT-COLD")).isEqualTo(1)
        }
        assertThat(coldOutcome).describedAs("no snapshot").isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        val none = refused(null, "ENT-NOREPLICA")
        assertThat(none.first).describedAs("no replica").isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(none.second).isEqualTo(1)
    }

    @Test
    fun `look-alike action strings do not confirm and the catalogue roles are the three`() {
        val roles = OperationRoles.rolesFor(DeploymentForm.SAAS, confirmerKey).keys
        assertThat(roles).isEqualTo(setOf("entity.admin", "tenant.admin", "tax.approver"))
        assertThat(OperationRoles.rolesFor(DeploymentForm.SAAS, "$confirmerKey ")).isEmpty()
        val f = Fixture()
        f.grant("IDN-C", "entity.admin", "ENT-KEYS")
        val deadline = Deadline.after(Duration.ofSeconds(1), f.clock)

        fun decide(action: String) =
            f.replica
                .decide(
                    AuthorizationRequest(tenantA, Principal("IDN-C", PrincipalKind.HUMAN), "ENT-KEYS", action),
                    deadline,
                ).allowed
        assertThat(decide(confirmerKey)).isTrue()
        assertThat(decide("$confirmerKey ")).isFalse()
        assertThat(decide("Entity-Config.Confirm")).isFalse()
        // a role the matrix lists for entity-config.manage but not for entity-config.confirm does not confirm
        val manageOnly = OperationRoles.rolesFor(DeploymentForm.SAAS, "entity-config.manage").keys - roles
        manageOnly.forEachIndexed { i, role ->
            val g = Fixture().also { it.grant("IDN-C", role, "ENT-MANAGE-$i") }
            assertThat(refused(g, "ENT-MANAGE-$i").first).describedAs(role).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        }
    }

    @Test
    fun `the confirmation records the user ID and no role for each of the seven holder sets, in both tables`() {
        val sets =
            listOf(
                setOf("entity.admin"),
                setOf("tenant.admin"),
                setOf("tax.approver"),
                setOf("entity.admin", "tenant.admin"),
                setOf("tenant.admin", "tax.approver"),
                setOf("entity.admin", "tax.approver"),
                setOf("entity.admin", "tenant.admin", "tax.approver"),
            )
        sets.forEachIndexed { i, held ->
            val f = Fixture()
            val entity = "ENT-REC-$i"
            val who = "IDN-USER-$i"
            held.forEach { f.grant(who, it, entity) }
            run(f.replica) { env ->
                val change = env.proposed(entity)
                assertThat(env.confirmedAs(who, entity, change.changeRef)).describedAs("$held").isEqualTo(HttpStatus.OK)
                assertThat(env.confirmations("identity_triple_change", entity))
                    .describedAs("$held change")
                    .isEqualTo(listOf<Pair<String?, String?>>(who to null))
                assertThat(env.confirmations("identity_triple", entity))
                    .describedAs("$held triple")
                    .isEqualTo(listOf<Pair<String?, String?>>(who to null))
            }
        }
    }

    @Test
    fun `two different users holding tax approver confirm two changes and the rows hold the two user IDs`() {
        val f = Fixture()
        val entity = "ENT-TWO"
        f.grant("IDN-TA-1", "tax.approver", entity)
        f.grant("IDN-TA-2", "tax.approver", entity)
        run(f.replica) { env ->
            val first = env.proposed(entity)
            assertThat(env.confirmedAs("IDN-TA-1", entity, first.changeRef)).isEqualTo(HttpStatus.OK)
            val second =
                env.inTenant(tenantA, proposer) {
                    env.context.getBean(ProposeIdentityTripleChange::class.java).handle(
                        ProposeCommand(
                            EntityRef(entity),
                            agent,
                            IdentityTriple("0195:U-B", "U-B", "G-B"),
                            LocalDate.of(2026, 3, 1),
                            PersonRef(proposer),
                        ),
                    )
                }
            assertThat(env.confirmedAs("IDN-TA-2", entity, second.changeRef)).isEqualTo(HttpStatus.OK)
            assertThat(env.confirmations("identity_triple_change", entity).map { it.first })
                .containsExactlyInAnyOrder("IDN-TA-1", "IDN-TA-2")
            assertThat(env.confirmations("identity_triple", entity).map { it.first })
                .containsExactlyInAnyOrder("IDN-TA-1", "IDN-TA-2")
            assertThat(env.confirmations("identity_triple_change", entity).map { it.second }.toSet())
                .isEqualTo(setOf<String?>(null))
        }
    }
}
