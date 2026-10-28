package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.adapters.http.PendingChangeAccepted
import com.dynokore.gst.tenancy.adapters.http.PendingChangeAdvice
import com.dynokore.gst.tenancy.application.election.ElectionChangeStore
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.LocalDate

/**
 * The two-step approval of an election change (INV-REQ-370, INV-REQ-422, INV-REQ-505, INV-REQ-1284; owner answer
 * INV-Q-10105): a change is stored PENDING, another person holding `election-change.approve` approves it, and only
 * then it takes effect. Through the real context over PostgreSQL with forced row-level security.
 */
class ElectionChangeTest {
    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    private val daily = RateSourceElectionRequest.Basis.DAILY
    private val monthly = RateSourceElectionRequest.Basis.MONTHLY_AVERAGE
    private val later = TODAY.plusDays(30)

    /** The election read of the rate source on [on]. */
    private fun rate(
        e: ElectionEnv,
        entity: EntityResponse,
        on: LocalDate,
    ) = e.status(entity, on).elections.single { it.kind == ElectionKind.EXCHANGE_RATE_SOURCE }

    private fun versions(
        env: ProfileEnv,
        tenant: TenantId,
        entity: EntityResponse,
        vp: String,
    ): Int {
        val history = env.context.getBean(PolicyHistory::class.java)
        val target = PolicyScope.entity(tenant, entity.entityRef).target(ScopeLevel.ENTITY)
        return env.inTx { history.versions(VariationPointKey(vp), target).size }
    }

    private fun ready(
        e: ElectionEnv,
        tenant: TenantId,
        entity: EntityResponse,
    ) {
        e.usualPeople(entity)
        e.staged(tenant, "SCOPE-SG", "1.0", sources = TWO_SOURCES)
        e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(30))
        e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.TRUE)
    }

    /** The counts a pending change must leave as they were: versions, audit entries, other events, election reads. */
    private fun snapshot(
        e: ElectionEnv,
        env: ProfileEnv,
        tenant: TenantId,
        entity: EntityResponse,
    ): List<Any> =
        listOf("VP-22", "VP-23", "VP-24").map { versions(env, tenant, entity, it) } +
            listOf("VP-22", "VP-23", "VP-24").map { e.auditEntries(entity, it) } +
            e.otherEvents(entity) +
            ElectionKind.entries.map { e.history(entity, it) } +
            e.status(entity, later).elections +
            e.status(entity).elections +
            env.captured.envelopes.count { !it.type.contains("AuditEntryRecorded") }

    private fun submitsPendingAndChangesNothing(
        kind: ElectionKind,
        first: (ElectionEnv, EntityResponse) -> Unit,
        change: (ElectionEnv, EntityResponse) -> Unit,
    ) {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                first(e, entity)
                val before = snapshot(e, env, tenant, entity)
                val requested = e.changeAudits(entity, "election-change.requested")
                val pending = e.pending { change(e, entity) }
                assertThat(pending.requestedBy).isEqualTo(ADMIN)
                assertThat(pending.effectiveFrom).isEqualTo(later)
                assertThat(snapshot(e, env, tenant, entity)).isEqualTo(before)
                assertThat(e.changeStates(entity)).containsExactly("PENDING")
                assertThat(e.changeAudits(entity, "election-change.requested")).isEqualTo(requested + 1)
                assertThat(e.history(entity, kind)).hasSize(1)
            }
        }
    }

    @Test
    fun `a change of VP-22 is 202 and leaves versions, audit entries, events and the election read as before`() =
        submitsPendingAndChangesNothing(
            ElectionKind.PURCHASE_DATE_BASIS,
            { e, entity -> e.elect(entity, "INVOICE_DATE") },
            { e, entity -> e.elect(entity, "POSTING_DATE", later) },
        )

    @Test
    fun `a change of VP-23 is 202 and leaves versions, audit entries, events and the election read as before`() =
        submitsPendingAndChangesNothing(
            ElectionKind.APPORTIONMENT_MODE,
            { e, entity -> e.elect(entity, "CLAIMABLE_PORTION") },
            { e, entity -> e.elect(entity, "ENTIRE_INVOICE", later) },
        )

    @Test
    fun `a change of VP-24 is 202 and leaves versions, audit entries, events and the election read as before`() =
        submitsPendingAndChangesNothing(
            ElectionKind.EXCHANGE_RATE_SOURCE,
            { e, entity -> e.rate(entity, "MAS", daily) },
            { e, entity -> e.rate(entity, "BNM", monthly, later) },
        )

    @Test
    fun `a first election is 201 and stored at once, a repeat is 200 and nothing is pending`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                assertThat(e.elect(entity, "INVOICE_DATE").statusCode).isEqualTo(HttpStatus.CREATED)
                assertThat(e.history(entity, ElectionKind.PURCHASE_DATE_BASIS)).hasSize(1)
                assertThat(e.rate(entity, "MAS", daily).statusCode).isEqualTo(HttpStatus.CREATED)
                assertThat(e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE)).hasSize(1)
                assertThat(e.elect(entity, "INVOICE_DATE").statusCode).isEqualTo(HttpStatus.OK)
                assertThat(e.changeStates(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `setIssuanceMode keeps the second confirmation and stores a change at once`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "AP_CALLBACK", 1)
                e.mode(entity, SetIssuanceModeRequest.Mode.RELAY)
                e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE, TODAY.plusDays(2))
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE).map { it.value })
                    .containsExactly("RELAY", "OBSERVE")
                assertThat(e.changeStates(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `the 202 answer carries the pending change body and status`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.rate(entity, "MAS", daily)
                val signal =
                    try {
                        e.rate(entity, "BNM", monthly, later)
                        null
                    } catch (accepted: PendingChangeAccepted) {
                        accepted
                    }
                val answer = PendingChangeAdvice().accepted(checkNotNull(signal))
                assertThat(answer.statusCode).isEqualTo(HttpStatus.ACCEPTED)
                val body = checkNotNull(answer.body)
                assertThat(body.election).isEqualTo("RATE_SOURCE")
                assertThat(body.source to body.basis?.value).isEqualTo("BNM" to "MONTHLY_AVERAGE")
                assertThat(body.changeRef).startsWith("CHG-")
                assertThat(body.requestedBy).isEqualTo(ADMIN)
            }
        }
    }

    private fun refusedKeepsPending(
        e: ElectionEnv,
        entity: EntityResponse,
        changeRef: String,
        caller: String,
        status: Int,
    ) {
        e.people.caller = caller
        val audits = e.changeAudits(entity, "election-change.approved")
        assertThat(e.refusal { e.approve(entity, changeRef) }!!.first).isEqualTo(status)
        assertThat(e.changeStates(entity)).containsExactly("PENDING")
        assertThat(e.changeAudits(entity, "election-change.approved")).isEqualTo(audits)
        assertThat(e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE)).hasSize(1)
    }

    @Test
    fun `an approval by the requester, by roles without the permission, by look-alikes and on another entity is 403`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.people.grant("identity:USR-011", entity.entityRef, "tax.approver")
                e.rate(entity, "MAS", daily)
                val change = e.pending { e.rate(entity, "BNM", monthly, later) }
                // the requester holds the permission as well and still may not approve own change
                e.people.grantPermission(ADMIN, entity.entityRef, ElectionPermissions.APPROVE)
                refusedKeepsPending(e, entity, change.changeRef, ADMIN, 403)
                refusedKeepsPending(e, entity, change.changeRef, OTHER_ADMIN, 403)
                refusedKeepsPending(e, entity, change.changeRef, ENTITY_ADMIN_ONLY, 403)
                refusedKeepsPending(e, entity, change.changeRef, "$APPROVER ", 403)
                refusedKeepsPending(e, entity, change.changeRef, NOBODY, 403)
                // the role tax.approver alone is no permission
                refusedKeepsPending(e, entity, change.changeRef, "identity:USR-011", 403)
                // a permission grant on another entity only
                e.people.grantPermission("identity:USR-012", "ENT-OTHER", ElectionPermissions.APPROVE)
                refusedKeepsPending(e, entity, change.changeRef, "identity:USR-012", 403)
            }
        }
    }

    @Test
    fun `a change that is unknown, of another entity, of another tenant or already decided is 404`() {
        run { e, env ->
            env.withGroup(2) { tenant, _, entities ->
                val (entity, sibling) = entities
                ready(e, tenant, entity)
                e.usualPeople(sibling)
                e.rate(entity, "MAS", daily)
                val change = e.pending { e.rate(entity, "BNM", monthly, later) }
                e.people.caller = APPROVER
                assertThat(e.refusal { e.approve(entity, "CHG-UNKNOWN") }!!.first).isEqualTo(404)
                assertThat(e.refusal { e.approve(entity, change.changeRef + " ") }!!.first).isEqualTo(404)
                assertThat(e.refusal { e.approve(sibling, change.changeRef) }!!.first).isEqualTo(404)
                assertThat(e.changeStates(entity)).containsExactly("PENDING")
                // a caller bound to another tenant reads no row of this one (RLS)
                env.withTenant { other ->
                    val otherGroup = env.group(other)
                    val foreign = env.entity(other, otherGroup)
                    e.usualPeople(foreign)
                    e.people.caller = APPROVER
                    assertThat(e.refusal { e.approve(foreign, change.changeRef) }!!.first).isEqualTo(404)
                    assertThat(e.refusal { e.approve(entity, change.changeRef) }!!.first).isEqualTo(404)
                }
                assertThat(e.changeStates(entity)).containsExactly("PENDING")
                assertThat(e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE)).hasSize(1)
            }
        }
    }

    @Test
    fun `INV-AC-1511 INV-AC-9515 a change of the rate source takes effect on approval from its effective date`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.rate(entity, "MAS", daily)
                val requestedAudits = e.changeAudits(entity, "election-change.requested")
                val change = e.pending { e.rate(entity, "BNM", monthly, later) }
                // without approval the stored election still equals A and one request entry exists
                assertThat(rate(e, entity, later).value).isEqualTo("MAS")
                assertThat(e.changeAudits(entity, "election-change.requested")).isEqualTo(requestedAudits + 1)
                assertThat(e.changeAudits(entity, "election-change.approved")).isZero()
                e.people.caller = APPROVER
                val approved = e.approve(entity, change.changeRef)
                assertThat(approved.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(approved.body!!.approvedBy).isEqualTo(APPROVER)
                assertThat(approved.body!!.requestedBy).isEqualTo(ADMIN)
                val history = e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE)
                assertThat(history.map { Triple(it.value, it.basis, it.effectiveFrom) })
                    .containsExactly(Triple("MAS", "DAILY", TODAY), Triple("BNM", "MONTHLY_AVERAGE", later))
                assertThat(history.last().setBy).isEqualTo(ADMIN)
                assertThat(history.last().reason).contains("approved by $APPROVER").contains("requested by $ADMIN")
                assertThat(rate(e, entity, later.minusDays(1)).value).isEqualTo("MAS")
                assertThat(rate(e, entity, later).value).isEqualTo("BNM")
                assertThat(rate(e, entity, later).basis).isEqualTo("MONTHLY_AVERAGE")
                assertThat(e.changeStates(entity)).containsExactly("APPROVED")
                // one request entry and one approval entry; the approval entry names both persons
                assertThat(e.changeAudits(entity, "election-change.requested")).isEqualTo(requestedAudits + 1)
                assertThat(e.changeAudits(entity, "election-change.approved")).isEqualTo(1)
            }
        }
    }

    @Test
    fun `a change of VP-22 and VP-23 approved by another person is written with the requester as actor`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.elect(entity, "INVOICE_DATE")
                e.elect(entity, "CLAIMABLE_PORTION")
                val basis = e.pending { e.elect(entity, "POSTING_DATE", later) }
                val mode = e.pending { e.elect(entity, "ENTIRE_INVOICE", later) }
                e.people.caller = APPROVER
                e.approve(entity, basis.changeRef)
                e.approve(entity, mode.changeRef)
                assertThat(e.history(entity, ElectionKind.PURCHASE_DATE_BASIS).map { it.value to it.setBy })
                    .containsExactly("INVOICE_DATE" to ADMIN, "POSTING_DATE" to ADMIN)
                assertThat(e.history(entity, ElectionKind.APPORTIONMENT_MODE).map { it.value })
                    .containsExactly("CLAIMABLE_PORTION", "ENTIRE_INVOICE")
                assertThat(e.changeStates(entity)).containsExactly("APPROVED", "APPROVED")
            }
        }
    }

    @Test
    fun `the value checks run again at approval and a failed check is 422 with the change pending`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.elect(entity, "INVOICE_DATE")
                e.rate(entity, "MAS", daily)
                val basis = e.pending { e.elect(entity, "POSTING_DATE", later) }
                val source = e.pending { e.rate(entity, "BNM", monthly, later) }
                e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.FALSE)
                // a newer scope pack, active before the effective date, no longer lists BNM
                e.staged(tenant, "SCOPE-SG", "2.0", sources = "[{\"sourceId\":\"MAS\",\"bases\":[\"DAILY\"]}]")
                e.activated(tenant, "SCOPE-SG", "2.0", TODAY.minusDays(1))
                e.people.caller = APPROVER
                val audits = e.changeAudits(entity, "election-change.approved")
                val events = e.otherEvents(entity)
                assertThat(e.refusal { e.approve(entity, basis.changeRef) }!!.first).isEqualTo(422)
                assertThat(e.refusal { e.approve(entity, source.changeRef) }!!.first).isEqualTo(422)
                assertThat(e.changeStates(entity)).containsExactly("PENDING", "PENDING")
                assertThat(e.history(entity, ElectionKind.PURCHASE_DATE_BASIS)).hasSize(1)
                assertThat(e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE)).hasSize(1)
                assertThat(e.changeAudits(entity, "election-change.approved")).isEqualTo(audits)
                assertThat(e.otherEvents(entity)).isEqualTo(events)
            }
        }
    }

    @Test
    fun `a replay of the approve call is 404 and one version only`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.rate(entity, "MAS", daily)
                val change = e.pending { e.rate(entity, "BNM", monthly, later) }
                e.people.caller = APPROVER
                e.approve(entity, change.changeRef)
                assertThat(e.refusal { e.approve(entity, change.changeRef) }!!.first).isEqualTo(404)
                assertThat(versions(env, tenant, entity, "VP-24")).isEqualTo(2)
                assertThat(e.changeAudits(entity, "election-change.approved")).isEqualTo(1)
            }
        }
    }

    @Test
    fun `audit entries count 1 after submit, 2 after approval and are unchanged by a refused approval`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.rate(entity, "MAS", daily)
                val total = { e.changeAudits(entity, "election-change.") }
                val base = total()
                val change = e.pending { e.rate(entity, "BNM", monthly, later) }
                assertThat(total() - base).isEqualTo(1)
                e.people.caller = ADMIN
                e.refusal { e.approve(entity, change.changeRef) }
                assertThat(total() - base).isEqualTo(1)
                e.people.caller = APPROVER
                e.approve(entity, change.changeRef)
                assertThat(total() - base).isEqualTo(2)
            }
        }
    }

    @Test
    fun `the shared setPolicy stays refused after an approval that threw and the marker is cleared`() {
        run { e, env ->
            env.withGroup(1) { tenant, group, entities ->
                val entity = entities.single()
                ready(e, tenant, entity)
                e.rate(entity, "MAS", daily)
                // a change whose date is already past: the policy writer refuses it at approval (INV-Q-10121 is open)
                val store = env.context.getBean(ElectionChangeStore::class.java)
                val past =
                    env.inTx {
                        val kind = ElectionKind.EXCHANGE_RATE_SOURCE
                        store.request(entity.entityRef, kind, "BNM", "MONTHLY_AVERAGE", TODAY.minusDays(3), ADMIN)
                    }
                e.people.caller = APPROVER
                assertThat(e.refusal { e.approve(entity, past.changeRef) }).isNotNull()
                assertThat(e.changeStates(entity)).containsExactly("PENDING")
                val refusal =
                    try {
                        env.setPolicy(
                            "VP-24",
                            ScopeLevel.ENTITY,
                            group,
                            entity,
                            "APPROVED_SOURCE",
                            "{\"source\":\"BNM\",\"basis\":\"MONTHLY_AVERAGE\"}",
                            "entity.admin",
                        )
                        null
                    } catch (refused: PolicyRefused) {
                        refused.refusal
                    }
                assertThat(refusal).isEqualTo(PolicyRefusal.CONSTRAINT_REFUSED)
                assertThat(versions(env, tenant, entity, "VP-24")).isEqualTo(1)
            }
        }
    }
}
