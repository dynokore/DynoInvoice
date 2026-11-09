package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.LocalDate

private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
    BoundaryFixture.runner().run { context ->
        context.startupFailure?.let { throw it }
        val env = ProfileEnv(context)
        block(ElectionEnv(env), env)
    }
}

/**
 * The next 1 July after the writer's clock: the clock is before it, so the change is dated in the future and the
 * policy writer accepts it.
 */
private val julyFirst: LocalDate =
    LocalDate.of(TODAY.year, 7, 1).let { if (it.isAfter(TODAY)) it else it.plusYears(1) }

private const val CLAIMABLE = "CLAIMABLE_PORTION"
private const val ENTIRE = "ENTIRE_INVOICE"
private const val APPROVER_U2 = "identity:USR-032"
private const val NO_PERMISSION_U3 = "identity:USR-033"

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

private fun versions(
    env: ProfileEnv,
    tenant: TenantId,
    entity: EntityResponse,
): Int {
    val history = env.context.getBean(PolicyHistory::class.java)
    val target = PolicyScope.entity(tenant, entity.entityRef).target(ScopeLevel.ENTITY)
    return env.inTx { history.versions(VariationPointKey("VP-23"), target).size }
}

/** The mode read on [on] and the counts a pending change must leave as they were. */
private fun state(
    e: ElectionEnv,
    env: ProfileEnv,
    tenant: TenantId,
    entity: EntityResponse,
): List<Any?> =
    listOf<Any?>(
        mode(e, entity, julyFirst.minusDays(1)),
        mode(e, entity, julyFirst),
        mode(e, entity, julyFirst.plusDays(1)),
        versions(env, tenant, entity),
        e.auditEntries(entity, "VP-23"),
        e.changeAudits(entity, "election-change."),
        e.otherEvents(entity),
        env.captured.envelopes.size,
    )

private fun mode(
    e: ElectionEnv,
    entity: EntityResponse,
    on: LocalDate,
) = e
    .status(entity, on)
    .elections
    .single { it.kind == ElectionKind.APPORTIONMENT_MODE }
    .value

/** The audit entries of the entity that name the change (event data as text), read as the superuser. */
private fun changeAuditData(
    entity: EntityResponse,
    action: String,
): List<String> =
    BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "select data::text from tenancy.outbox where topic = 'gst.platform.audit-entry-recorded.v1' " +
                    "and data::text like ? and data::text like ?",
            ).use { s ->
                s.setString(1, "%${entity.entityRef}%")
                s.setString(2, "%$action%")
                s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
    }

/**
 * The apportionment mode (VP-23) change at the tenancy-svc boundary (INV-REQ-370): recordElection stores a change of
 * a made election PENDING, approveElectionChange applies it from its effective date. Through the real context over
 * PostgreSQL with forced row-level security; the writer's clock is before 1 July.
 */
class ElectionModeChangeTest {
    private fun refused(
        e: ElectionEnv,
        entity: EntityResponse,
        changeRef: String,
        caller: String,
    ): Pair<Int, String>? {
        e.people.caller = caller
        return e.refusal { e.approve(entity, changeRef) }
    }

    @Test
    fun `INV-AC-394 a mode change is 202 pending and changes nothing, an approval without permission is 403`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.people.grant(APPROVER_U2, entity.entityRef, "tax.approver")
                e.people.grantRole(APPROVER_U2, entity.entityRef, "tax.approver")
                e.people.grant(NO_PERMISSION_U3, entity.entityRef, "entity.admin")
                e.elect(entity, CLAIMABLE)
                val before = state(e, env, tenant, entity)
                assertThat(before.take(3)).containsOnly(CLAIMABLE)

                e.people.caller = ADMIN
                val change = e.pending { e.elect(entity, ENTIRE, julyFirst) }
                assertThat(change.requestedBy).isEqualTo(ADMIN)
                assertThat(change.effectiveFrom).isEqualTo(julyFirst)
                assertThat(mode(e, entity, julyFirst.minusDays(1))).isEqualTo(CLAIMABLE)
                assertThat(mode(e, entity, julyFirst.plusDays(1))).isEqualTo(CLAIMABLE)
                // versions, VP-23 audit entries, other events and published events equal the values before the call
                assertThat(state(e, env, tenant, entity).drop(3).take(2)).isEqualTo(before.drop(3).take(2))
                assertThat(e.otherEvents(entity)).isEqualTo(before[6])
                // the one audit entry added is the request (`election-change.requested`); only that event is published
                assertThat(e.changeAudits(entity, "election-change.requested")).isEqualTo(1L)
                assertThat(e.changeAudits(entity, "election-change.") - (before[5] as Long)).isEqualTo(1L)
                assertThat(e.changeAudits(entity, "election-change.approved")).isZero()
                assertThat(env.captured.envelopes.size - (before[7] as Int)).isEqualTo(1)
                assertThat(e.changeStates(entity)).containsExactly("PENDING")

                // the requester U1 and the holder of no approve permission U3 are refused; the look-alikes too
                val unchanged = state(e, env, tenant, entity)
                e.people.asked.clear()
                assertThat(refused(e, entity, change.changeRef, ADMIN)).isEqualTo(403 to "SRT-004")
                // the exact permission key asked at the boundary, never a look-alike such as "election-change.approve "
                assertThat(e.people.asked).contains(ElectionPermissions.APPROVE)
                assertThat(e.people.asked).doesNotContain(ElectionPermissions.APPROVE + " ")
                assertThat(refused(e, entity, change.changeRef, NO_PERMISSION_U3)).isEqualTo(403 to "SRT-004")
                assertThat(refused(e, entity, change.changeRef, "$APPROVER ")).isEqualTo(403 to "SRT-004")
                e.people.grantRole("identity:USR-034", "ENT-OTHER", "tax.approver")
                assertThat(refused(e, entity, change.changeRef, "identity:USR-034")).isEqualTo(403 to "SRT-004")
                assertThat(e.changeStates(entity)).containsExactly("PENDING")
                assertThat(state(e, env, tenant, entity)).isEqualTo(unchanged)
                assertThat(mode(e, entity, julyFirst.minusDays(1))).isEqualTo(CLAIMABLE)
                assertThat(mode(e, entity, julyFirst.plusDays(1))).isEqualTo(CLAIMABLE)
            }
        }
    }

    @Test
    fun `INV-AC-1647 an approved mode change is audited with both user ids and applies from its effective date`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.people.grantRole(APPROVER_U2, entity.entityRef, "tax.approver")
                e.elect(entity, CLAIMABLE)
                e.people.caller = ADMIN
                val change = e.pending { e.elect(entity, ENTIRE, julyFirst) }

                e.people.caller = APPROVER_U2
                val approved = e.approve(entity, change.changeRef)
                assertThat(approved.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(approved.body!!.requestedBy).isEqualTo(ADMIN)
                assertThat(approved.body!!.approvedBy).isEqualTo(APPROVER_U2)
                assertThat(e.changeStates(entity)).containsExactly("APPROVED")

                val entries = changeAuditData(entity, "election-change.approved")
                assertThat(entries).hasSize(1)
                val entry = entries.single()
                assertThat(entry).contains(ADMIN).contains(APPROVER_U2).contains(julyFirst.toString())
                assertThat(entry).contains(ENTIRE)
                listOf("entity.admin", "tenant.admin", "tax.approver").forEach { role ->
                    assertThat(entry).doesNotContain(role)
                }
                assertThat(mode(e, entity, julyFirst.minusDays(1))).isEqualTo(CLAIMABLE)
                assertThat(mode(e, entity, julyFirst)).isEqualTo(ENTIRE)
                assertThat(e.history(entity, ElectionKind.APPORTIONMENT_MODE).map { it.value to it.effectiveFrom })
                    .containsExactly(CLAIMABLE to TODAY, ENTIRE to julyFirst)
            }
        }
    }
}
