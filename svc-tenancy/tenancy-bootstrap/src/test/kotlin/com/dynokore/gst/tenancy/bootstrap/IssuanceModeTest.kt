package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.election.ElectionGateDecision
import com.dynokore.gst.tenancy.application.election.ElectionOutcome
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionStatus
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.IssuanceMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The issuance mode (INV-REQ-323, INV-REQ-422, INV-REQ-505, INV-REQ-1283 to INV-REQ-1286) through the real context
 * over PostgreSQL with forced row-level security. Test names carry the criterion key.
 */
class IssuanceModeTest {
    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    private val ack = "AP_CALLBACK"

    // ---- issuance mode -------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-9513 INV-AC-1549 a change of the issuance mode needs a different person to confirm`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), ack, 1)
                e.mode(entity, SetIssuanceModeRequest.Mode.RELAY)
                val published = e.issuanceEvents().size
                val later = TODAY.plusDays(5)
                // the one request names the caller as the confirmer: refused 403 SRT-004, nothing stored or published
                assertThat(e.refusal { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE, later, ADMIN) })
                    .isEqualTo(403 to "SRT-004")
                // a person without the role, an unknown person and an id with a trailing space cannot be proven
                listOf(NOBODY, "identity:UNKNOWN", "$OTHER_ADMIN ").forEach { who ->
                    assertThat(e.refusal { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE, later, who) })
                        .isEqualTo(403 to "SRT-004")
                }
                // a missing confirmer is 400 (the command takes null as the absent member)
                listOf(null, "").forEach { missing ->
                    val outcome =
                        env.inTenant(tenant) {
                            e.commands.setIssuanceMode(tenant, entity.entityRef, IssuanceMode.OBSERVE, later, missing)
                        }
                    assertThat(outcome).isInstanceOf(ElectionOutcome.Invalid::class.java)
                }
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE).map { it.value }).containsExactly("RELAY")
                assertThat(e.issuanceEvents()).hasSize(published)
                // a tax.approver other than the caller confirms
                val response = e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE, later, APPROVER).body!!
                assertThat(response.mode.value).isEqualTo("OBSERVE")
                val history = e.history(entity, ElectionKind.ISSUANCE_MODE)
                assertThat(history.map { it.value to it.effectiveFrom })
                    .containsExactly("RELAY" to TODAY, "OBSERVE" to later)
                assertThat(history.last().setBy).isEqualTo(ADMIN)
                assertThat(history.last().reason).contains(ADMIN).contains(APPROVER)
                assertThat(e.issuanceEvents()).hasSize(published + 1)
            }
        }
    }

    @Test
    fun `INV-AC-9512 the issuance mode has no default and OBSERVE is refused without an elected route`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                // no default: nothing is stored and the status lists the mode as outstanding
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE)).isEmpty()
                assertThat(e.status(entity).outstanding).contains(ElectionKind.ISSUANCE_MODE)
                assertThat(env.inTenant(tenant) { e.gate.check(entity.entityRef, TODAY) })
                    .isInstanceOf(ElectionGateDecision.Refused::class.java)
                // the entity is on no route-replica row
                val none = e.reason { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE) }
                assertThat(none).contains(entity.entityRef).contains("no Access Point connection")
                // a row of its tenant that says no route
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), null, 1)
                val noRoute = e.reason { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE) }
                assertThat(noRoute).contains("AP-1").contains("no acknowledgement route")
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE)).isEmpty()
                // a row with an elected route
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "POLLING", 2)
                assertThat(
                    e
                        .mode(entity, SetIssuanceModeRequest.Mode.OBSERVE)
                        .body!!
                        .mode.value,
                ).isEqualTo("OBSERVE")
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE).single().setBy).isEqualTo(ADMIN)
            }
        }
    }

    @Test
    fun `INV-AC-1618 OBSERVE is refused with a reason naming the missing acknowledgement channel`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-7", listOf(entity.entityRef), null, 1)
                val outcome =
                    env.inTenant(tenant) {
                        e.commands.setIssuanceMode(tenant, entity.entityRef, IssuanceMode.OBSERVE, TODAY, OTHER_ADMIN)
                    }
                assertThat(outcome).isInstanceOf(ElectionOutcome.Refused::class.java)
                val reason = (outcome as ElectionOutcome.Refused).reason
                assertThat(reason).contains("AP-7").contains("callback, polling or report import")
                assertThat(e.refusal { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE) }!!.first).isEqualTo(422)
                assertThat(e.refusal { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE) }!!.second)
                    .startsWith("POL-014")
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE)).isEmpty()
                // RELAY and NONE need no route
                e.mode(entity, SetIssuanceModeRequest.Mode.NONE)
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE).single().value).isEqualTo("NONE")
            }
        }
    }

    @Test
    fun `INV-AC-2272 go-live is refused until the issuance mode is elected and confirmed`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), ack, 1)
                // the other three are not outstanding: facts recorded false, date basis and rate source elected
                e.facts.set(entity.entityRef, "recordsPurchases", FactAnswer.FALSE)
                e.facts.set(entity.entityRef, "hasForeignCurrencyDocuments", FactAnswer.FALSE)
                e.elect(entity, "INVOICE_DATE")
                val first = env.inTenant(tenant) { e.gate.check(entity.entityRef, TODAY) }
                assertThat(first).isInstanceOf(ElectionGateDecision.Refused::class.java)
                assertThat((first as ElectionGateDecision.Refused).outstanding)
                    .containsExactly(ElectionKind.ISSUANCE_MODE)
                e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE)
                assertThat(env.inTenant(tenant) { e.gate.check(entity.entityRef, TODAY) })
                    .isInstanceOf(ElectionGateDecision.Allowed::class.java)
                assertThat(env.inTenant(tenant) { e.gate.check("ENT-NONE", TODAY) })
                    .isEqualTo(ElectionGateDecision.EntityNotFound)
            }
        }
    }

    @Test
    fun `INV-AC-1441 INV-AC-2269 INV-AC-2270 INV-AC-2271 the election status shows the elections outstanding`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                // Wave 2 part: a new entity lists all four as outstanding; the holds themselves run in Wave 3
                val status = e.status(entity)
                assertThat(status.outstanding).containsExactlyElementsOf(ElectionKind.entries)
                assertThat(status.elections.map { it.status }.toSet()).containsExactly(ElectionStatus.OUTSTANDING)
                e.elect(entity, "INVOICE_DATE")
                assertThat(e.status(entity).outstanding).doesNotContain(ElectionKind.PURCHASE_DATE_BASIS)
            }
        }
    }

    // ---- EntityProfileChanged -------------------------------------------------------------------------------

    @Test
    fun `a change from Relay to Observe on a later date publishes one event with both entries`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), ack, 1)
                e.mode(entity, SetIssuanceModeRequest.Mode.RELAY)
                val later = TODAY.plusDays(10)
                val before = e.profileEvents().size
                e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE, later)
                val published = e.profileEvents().drop(before)
                assertThat(published).hasSize(1)
                val event = published.single()
                val changed = event["changedAttributes"].values().single()
                assertThat(changed["attribute"].asString()).isEqualTo("issuanceMode")
                assertThat(changed["oldValue"].asString()).isEqualTo("RELAY")
                assertThat(changed["newValue"].asString()).isEqualTo("OBSERVE")
                assertThat(event["effectiveDate"].asString()).isEqualTo(later.toString())
                val profile =
                    event["node"]["profile"].values().map { it["attribute"].asString() to it["value"].asString() }
                assertThat(profile).containsExactly("issuanceMode" to "RELAY", "issuanceMode" to "OBSERVE")
                assertThat(event["node"]["profile"].values().map { it["effectiveDate"].asString() })
                    .containsExactly(TODAY.toString(), later.toString())
            }
        }
    }

    @Test
    fun `the structure version rises with every publication and a status change publishes no issuanceMode entry`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.mode(entity, SetIssuanceModeRequest.Mode.RELAY)
                env.set(
                    entity,
                    com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest(
                        com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest.StatusKind.PARTIAL_EXEMPTION,
                        TODAY,
                        partiallyExempt = true,
                        basis = "review",
                    ),
                )
                e.mode(entity, SetIssuanceModeRequest.Mode.NONE, TODAY.plusDays(3))
                val versions = e.profileEvents().map { it["node"]["structureVersion"].asLong() }
                assertThat(versions).hasSize(3).isSorted()
                assertThat(versions.toSet()).hasSize(3)
                val statusEvent = e.profileEvents()[1]
                assertThat(statusEvent["changedAttributes"].values().map { it["attribute"].asString() })
                    .doesNotContain("issuanceMode")
                // the node state of the status event still carries the issuance entry (the full state)
                assertThat(statusEvent["node"]["profile"].values().map { it["attribute"].asString() })
                    .contains("issuanceMode", "partiallyExempt")
                assertThat(tenant).isNotNull
            }
        }
    }
}
