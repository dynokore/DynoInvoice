package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * The replay of an election and the change on a stored date (rulings INV-Q-10046, INV-Q-11868, INV-Q-11870): a first
 * election answers 201, the same election again answers 200 and writes nothing, and another value on the stored date
 * is a change that waits for the two-step flow (422 POL-014, nothing stored).
 */
class ElectionReplayTest {
    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

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
        e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "AP_CALLBACK", 1)
        e.staged(tenant, "SCOPE-SG", "1.0", sources = TWO_SOURCES)
        e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(30))
        e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.TRUE)
    }

    @Test
    fun `a first election answers 201 and the same election again answers 200 for VP-22 VP-23 VP-24 and VP-25`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                val daily = RateSourceElectionRequest.Basis.DAILY
                val steps: List<Pair<String, () -> ResponseEntity<*>>> =
                    listOf(
                        "VP-22" to { e.elect(entity, "INVOICE_DATE") },
                        "VP-23" to { e.elect(entity, "CLAIMABLE_PORTION") },
                        "VP-24" to { e.rate(entity, "MAS", daily) },
                        "VP-25" to { e.mode(entity, SetIssuanceModeRequest.Mode.RELAY) },
                    )
                steps.forEach { (vp, call) ->
                    val first = call()
                    assertThat(first.statusCode).describedAs(vp).isEqualTo(HttpStatus.CREATED)
                    val audits = e.auditEntries(entity, vp)
                    val versions = versions(env, tenant, entity, vp)
                    val events = e.issuanceEvents().size
                    val second = call()
                    assertThat(second.statusCode).describedAs(vp).isEqualTo(HttpStatus.OK)
                    assertThat(second.body).describedAs(vp).isEqualTo(first.body)
                    assertThat(versions(env, tenant, entity, vp)).describedAs(vp).isEqualTo(1).isEqualTo(versions)
                    assertThat(e.auditEntries(entity, vp)).describedAs(vp).isEqualTo(audits)
                    assertThat(e.issuanceEvents()).describedAs(vp).hasSize(events)
                }
            }
        }
    }

    @Test
    fun `another value on the stored date is a change and is refused 422 with nothing stored`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                val daily = RateSourceElectionRequest.Basis.DAILY
                val monthly = RateSourceElectionRequest.Basis.MONTHLY_AVERAGE
                e.elect(entity, "INVOICE_DATE")
                e.elect(entity, "CLAIMABLE_PORTION")
                e.rate(entity, "MAS", daily)
                val before = listOf("VP-22", "VP-23", "VP-24").map { versions(env, tenant, entity, it) }
                val audits = listOf("VP-22", "VP-23", "VP-24").map { e.auditEntries(entity, it) }
                listOf<() -> Unit>(
                    { e.elect(entity, "POSTING_DATE") },
                    { e.elect(entity, "ENTIRE_INVOICE") },
                    { e.rate(entity, "BNM", daily) },
                    { e.rate(entity, "MAS", monthly) },
                    { e.elect(entity, "POSTING_DATE", TODAY.plusDays(3)) },
                ).forEach { call -> assertThat(e.refusal(call)!!.first).isEqualTo(422) }
                assertThat(e.reason { e.elect(entity, "POSTING_DATE") }).contains("two-step flow")
                assertThat(
                    listOf("VP-22", "VP-23", "VP-24").map { versions(env, tenant, entity, it) },
                ).isEqualTo(before)
                assertThat(listOf("VP-22", "VP-23", "VP-24").map { e.auditEntries(entity, it) }).isEqualTo(audits)
                // the same value on another date is a new election, not a change
                assertThat(e.elect(entity, "INVOICE_DATE", TODAY.plusDays(4)).statusCode).isEqualTo(HttpStatus.CREATED)
            }
        }
    }

    @Test
    fun `RELAY then OBSERVE on the stored date by another person is written as a new version and answers 201`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.mode(entity, SetIssuanceModeRequest.Mode.RELAY)
                assertThat(versions(env, tenant, entity, "VP-25")).isEqualTo(1)
                val observe = e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE)
                assertThat(observe.statusCode).isEqualTo(HttpStatus.CREATED)
                assertThat(versions(env, tenant, entity, "VP-25")).isEqualTo(2)
                assertThat(e.refusal { e.mode(entity, SetIssuanceModeRequest.Mode.RELAY, TODAY, ADMIN) })
                    .isEqualTo(403 to "SRT-004")
            }
        }
    }

    @Test
    fun `a replay refused by the caller permission keeps 403 and the OBSERVE replay checks the route first`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), null, 2)
                val refused = e.reason { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE) }
                assertThat(refused).contains("OBSERVE is refused")
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "AP_CALLBACK", 3)
                assertThat(e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE).statusCode).isEqualTo(HttpStatus.OK)
            }
        }
    }
}
