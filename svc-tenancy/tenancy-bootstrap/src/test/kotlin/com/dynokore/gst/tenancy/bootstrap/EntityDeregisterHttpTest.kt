package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.contract.model.DeregisterEntityRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GoLiveRequest
import com.dynokore.gst.tenancy.contract.model.SuspendEntityRequest
import com.dynokore.gst.tenancy.contract.model.UnsubmittedOverrideAcknowledgement
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.GoLiveGate
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.serviceruntime.http.Problems
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * suspendEntity and deregisterEntity through the controller of the real composition over PostgreSQL (INV-T-4931 on the
 * use cases of INV-T-16414): the response status, the refusal detail, the stored `lifecycle_state` and the outbox rows
 * of the call, compared by equality. The trigger-coverage and unsubmitted-documents ports are stubs (their producers
 * come in Wave 3, coordinator ruling INV-Q-4900).
 */
class EntityDeregisterHttpTest {
    // ---- suspendEntity ---------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-1980 probe suspendEntity sets SUSPENDED, not LIVE, and retracts nothing`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                assertThat(callGoLive(l.env, entity).status).isEqualTo(200)
                val certifications = l.e.rows(entity)
                val others = outboxRows(entity, "%").size - profileEventRows(entity).size
                val structure = structureEventRows(entity).size
                val answer = callSuspend(l.env, entity)
                assertThat(answer.status).isEqualTo(200)
                assertThat(answer.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.SUSPENDED)
                assertThat(storedState(entity)).isEqualTo("SUSPENDED")
                assertThat(storedState(entity)).isNotEqualTo("LIVE")
                val change = profileEventRows(entity).last()["changedAttributes"]
                assertThat(change[0]["attribute"].asString()).isEqualTo("lifecycleState")
                assertThat(change[0]["newValue"].asString()).isEqualTo("SUSPENDED")
                assertThat(l.e.rows(entity)).isEqualTo(certifications)
                // the one new row besides the profile change is the audit entry of the suspension
                assertThat(outboxRows(entity, "%").size - profileEventRows(entity).size).isEqualTo(others + 1)
                assertThat(auditRows(entity, "entity.suspended").size).isEqualTo(1)
                assertThat(structureEventRows(entity).size).isEqualTo(structure)
            }
        }
    }

    // ---- deregisterEntity ----

    @Test
    fun `probe suspendEntity writes one audit entry with actor, action, scope and reason`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                assertThat(callGoLive(l.env, entity).status).isEqualTo(200)
                assertThat(callSuspendWith(l.env, entity, "maintenance").status).isEqualTo(200)
                val rows = auditRows(entity, "entity.suspended")
                assertThat(rows.size).isEqualTo(1)
                val text = rows[0].toString()
                assertThat(text).contains("\"$ADMIN\"", "\"entity.suspended\"", "\"${entity.entityRef}\"")
                assertThat(text).contains("\"maintenance\"")
            }
        }
    }

    @Test
    fun `probe suspendEntity stores the reason as given, not trimmed or case-folded`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                assertThat(callSuspendWith(l.env, entity, "maintenance").status).isEqualTo(200)
                val text = auditRows(entity, "entity.suspended").single().toString()
                assertThat(text).contains("\"maintenance\"")
                assertThat(text).doesNotContain("\"maintenance \"").doesNotContain("\"Maintenance\"")
            }
        }
    }

    @Test
    fun `probe suspendEntity refused with 403 writes no audit entry and keeps the state`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                assertThat(callGoLive(l.env, entity).status).isEqualTo(200)
                l.people.caller = FINANCE_CALLER
                l.people.grant(FINANCE_CALLER, entity.entityRef, LIFECYCLE_FINANCE_VIEWER)
                val answer = callSuspendWith(l.env, entity, "maintenance")
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(403, "SRT-004"))
                assertThat(storedState(entity)).isEqualTo("LIVE")
                assertThat(auditRows(entity, "entity.suspended")).isEmpty()
            }
        }
    }

    @Test
    fun `probe suspendEntity of an unknown entity is 404 and writes no audit entry`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                val unknown = entity.copy(entityRef = "ENT-NOSUCH")
                l.people.grant(ADMIN, unknown.entityRef, ElectionRoles.ENTITY_ADMIN)
                val answer = callSuspendWith(l.env, unknown, "maintenance")
                assertThat(answer.status).isEqualTo(404)
                assertThat(auditRows(unknown, "entity.suspended")).isEmpty()
                assertThat(auditRows(entity, "entity.suspended")).isEmpty()
            }
        }
    }

    @Test
    fun `probe suspendEntity and deregisterEntity by a caller holding only election-change list are 403`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                assertThat(callGoLive(l.env, entity).status).isEqualTo(200)
                l.people.caller = FINANCE_CALLER
                l.people.grantPermission(FINANCE_CALLER, entity.entityRef, "election-change.list")
                val suspended = callSuspendWith(l.env, entity, "maintenance")
                val deregistered = callDeregister(l.env, entity)
                assertThat(listOf(suspended.status, deregistered.status)).isEqualTo(listOf(403, 403))
                assertThat(storedState(entity)).isEqualTo("LIVE")
            }
        }
    }

    @Test
    fun `probe deregisterEntity by a caller without entity-config manage is 403 and keeps the state`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                l.people.caller = FINANCE_CALLER
                l.people.grant(FINANCE_CALLER, entity.entityRef, LIFECYCLE_FINANCE_VIEWER)
                val answer = callDeregister(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(403, "SRT-004"))
                assertThat(storedState(entity)).isNotEqualTo("DEREGISTERED")
            }
        }
    }

    @Test
    fun `probe suspendEntity by a caller who holds entity-config manage is accepted`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                assertThat(callSuspendWith(l.env, entity, "maintenance").status).isEqualTo(200)
                assertThat(storedState(entity)).isEqualTo("SUSPENDED")
            }
        }
    }

    @Test
    fun `INV-AC-1051 probe deregisterEntity with an unsubmitted document and no override is 409 TNC-008 and changes nothing`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                lifecycleRegistered(l, entity)
                l.ports.unsubmitted = 2
                val before = storedState(entity)
                val rows = profileEventRows(entity).size
                val answer = callDeregister(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(409, "TNC-008"))
                assertThat(storedState(entity)).isEqualTo(before)
                assertThat(profileEventRows(entity).size).isEqualTo(rows)
                assertThat(overrideRows(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe deregisterEntity with the override is DEREGISTERED and the acknowledgement is recorded`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                lifecycleRegistered(l, entity)
                l.ports.unsubmitted = 2
                val rows = profileEventRows(entity).size
                val structure = structureEventRows(entity).size
                val ack = UnsubmittedOverrideAcknowledgement(ADMIN, "two drafts are abandoned")
                val answer = callDeregister(l.env, entity, override = ack)
                assertThat(answer.status).describedAs(answer.detail).isEqualTo(200)
                assertThat(answer.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.DEREGISTERED)
                assertThat(storedState(entity)).isEqualTo("DEREGISTERED")
                assertThat(
                    overrideRows(entity),
                ).isEqualTo(listOf(listOf(ADMIN, "two drafts are abandoned", TODAY.toString())))
                assertThat(profileEventRows(entity).size - rows).isEqualTo(1)
                assertThat(structureEventRows(entity).size).isEqualTo(structure)
            }
        }
    }

    @Test
    fun `probe deregisterEntity publishes registrationEndDate first and lifecycleState second in one event`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                lifecycleRegistered(l, entity)
                val rows = profileEventRows(entity).size
                assertThat(callDeregister(l.env, entity).status).isEqualTo(200)
                val events = profileEventRows(entity)
                assertThat(events.size - rows).isEqualTo(1)
                val event = events.last()
                val changes = event["changedAttributes"]
                assertThat(changes.size()).isEqualTo(2)
                assertThat(listOf(changes[0]["attribute"].asString(), changes[0]["newValue"].asString()))
                    .isEqualTo(listOf("registrationEndDate", "2026-08-15"))
                assertThat(listOf(changes[1]["attribute"].asString(), changes[1]["newValue"].asString()))
                    .isEqualTo(listOf("lifecycleState", "DEREGISTERED"))
                assertThat(event["effectiveDate"].asString()).isEqualTo(TODAY.toString())
                val profile =
                    event["node"]["profile"].values().filter {
                        it["attribute"].asString() ==
                            "registrationEndDate"
                    }
                assertThat(profile.map { it["value"].asString() }).isEqualTo(listOf("2026-08-15"))
                val text = event.toString()
                listOf("registrationEnd\"", "deregistrationDate", "obligationEndDate").forEach {
                    assertThat(text).doesNotContain(it)
                }
            }
        }
    }

    @Test
    fun `probe deregisterEntity records the FINAL period 1 Jul to 15 Aug 2026 on F8 unconfirmed`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                lifecycleRegistered(l, entity)
                assertThat(callDeregister(l.env, entity).status).isEqualTo(200)
                val final = PeriodEnv(l.env).records(entity).last()
                assertThat(listOf(final.start, final.end, final.returnForm))
                    .isEqualTo(listOf(day("2026-07-01"), day("2026-08-15"), "F8"))
                assertThat(final.kinds.map { it.name }).isEqualTo(listOf("FINAL"))
                assertThat(final.confirmation.name).isEqualTo("UNCONFIRMED")
            }
        }
    }

    @Test
    fun `probe deregisterEntity refused by the final period is 422 POL-014 and records nothing`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                l.ports.unsubmitted = 1
                val before = storedState(entity)
                val rows = profileEventRows(entity).size
                val answer =
                    callDeregister(l.env, entity, override = UnsubmittedOverrideAcknowledgement(ADMIN, "abandoned"))
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(storedState(entity)).isEqualTo(before)
                assertThat(profileEventRows(entity).size).isEqualTo(rows)
                assertThat(overrideRows(entity)).isEmpty()
            }
        }
    }

    private companion object {
        const val FINANCE_CALLER = "identity:USR-020"
    }
}
