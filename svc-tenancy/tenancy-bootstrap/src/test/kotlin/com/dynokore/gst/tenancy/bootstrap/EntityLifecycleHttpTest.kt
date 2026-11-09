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
 * goLive and its second confirmation through the controller of the real composition over PostgreSQL (INV-T-4931 on the
 * use cases of INV-T-16414): the response status, the refusal detail, the stored `lifecycle_state` and the outbox rows
 * of the call, compared by equality. The trigger-coverage and unsubmitted-documents ports are stubs (their producers
 * come in Wave 3, coordinator ruling INV-Q-4900).
 */
class EntityLifecycleHttpTest {
    // ---- goLive -----------------------------------------------------------------------------------------------

    @Test
    fun `probe goLive with the trigger-coverage port unbound in profile prod starts and is refused naming it`() {
        lifecycleProd { env ->
            env.withEntity { _, entity ->
                val people = env.context.getBean(TestPeople::class.java)
                people.caller = ADMIN
                people.grant(ADMIN, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
                people.grant(APPROVER, entity.entityRef, ElectionRoles.TAX_APPROVER)
                val answer = callGoLive(env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains(GoLiveGate.COVERAGE_UNKNOWN)
                assertThat(storedState(entity)).isNotEqualTo("LIVE")
                assertThat(profileEventRows(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe deregisterEntity with the documents count unbound in profile prod is 422 POL-014 naming the input`() {
        lifecycleProd { env ->
            env.withEntity { _, entity ->
                val people = env.context.getBean(TestPeople::class.java)
                people.caller = ADMIN
                people.grant(ADMIN, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
                val before = storedState(entity)
                val answer = callDeregister(env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("unsubmitted-documents-count")
                assertThat(storedState(entity)).isEqualTo(before)
                assertThat(profileEventRows(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe goLive with no certification record is refused naming the sandbox certification`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                BoundaryFixture.asSuperuser { c ->
                    c
                        .prepareStatement(
                            "delete from tenancy.sandbox_certification " +
                                "where entity_id = (select id from tenancy.entity where ref = ?)",
                        ).use {
                            it.setString(1, entity.entityRef)
                            it.executeUpdate()
                        }
                }
                val before = storedState(entity)
                val rows = profileEventRows(entity).size
                val answer = callGoLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("sandbox certification")
                assertThat(storedState(entity)).isEqualTo(before)
                assertThat(profileEventRows(entity).size).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `INV-AC-1064 goLive whose latest certification is FAILED with s1 and s2 is refused naming both`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                l.e.actAs(CERTIFIER, entity, RECORD_KEY)
                l.e.certify(entity, CertificationOutcome.FAILED, listOf("s1", "s2"))
                l.people.caller = ADMIN
                val before = storedState(entity)
                val rows = profileEventRows(entity).size
                val answer = callGoLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("s1").contains("s2")
                assertThat(storedState(entity)).isEqualTo(before)
                assertThat(storedState(entity)).isNotEqualTo("LIVE")
                assertThat(profileEventRows(entity).size).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `probe goLive with a trigger class not covered is refused naming it`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                l.ports.uncovered = listOf("CREDIT_NOTE")
                val answer = callGoLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("CREDIT_NOTE")
                assertThat(storedState(entity)).isNotEqualTo("LIVE")
            }
        }
    }

    @Test
    fun `probe goLive whose latest record is PASSED and all items met is LIVE with one EntityProfileChanged`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                val before = profileEventRows(entity).size
                val answer = callGoLive(l.env, entity)
                assertThat(answer.status).describedAs(answer.detail).isEqualTo(200)
                assertThat(answer.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.LIVE)
                assertThat(storedState(entity)).isEqualTo("LIVE")
                val rows = profileEventRows(entity)
                assertThat(rows.size - before).isEqualTo(1)
                val change = rows.last()["changedAttributes"]
                assertThat(change.size()).isEqualTo(1)
                assertThat(change[0]["attribute"].asString()).isEqualTo("lifecycleState")
                assertThat(change[0]["newValue"].asString()).isEqualTo("LIVE")
                assertThat(rows.last()["effectiveDate"].asString()).isEqualTo(TODAY.toString())
            }
        }
    }

    @Test
    fun `probe goLive writes no OrganisationStructureChanged row`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                val before = structureEventRows(entity).size
                assertThat(callGoLive(l.env, entity).status).isEqualTo(200)
                assertThat(structureEventRows(entity).size - before).isEqualTo(0)
            }
        }
    }

    @Test
    fun `probe goLive repeated keeps the state LIVE and writes no second outbox row`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                assertThat(callGoLive(l.env, entity).status).isEqualTo(200)
                val rows = profileEventRows(entity).size
                val again = callGoLive(l.env, entity)
                assertThat(again.status).isEqualTo(200)
                assertThat(again.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.LIVE)
                assertThat(storedState(entity)).isEqualTo("LIVE")
                assertThat(profileEventRows(entity).size).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `probe goLive by a caller without entity-config manage is 403 and changes nothing`() {
        lifecycleRun { l ->
            l.env.withEntity { _, entity ->
                lifecycleReady(l, entity)
                l.people.caller = FINANCE_CALLER
                l.people.grant(FINANCE_CALLER, entity.entityRef, LIFECYCLE_FINANCE_VIEWER)
                val answer = callGoLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(403, "SRT-004"))
                assertThat(storedState(entity)).isNotEqualTo("LIVE")
            }
        }
    }

    // ---- second confirmation of goLive ---------------------------------------------------------------------------

    private fun confirmation(
        confirmerRoles: Array<String>,
        confirmer: String = LIFECYCLE_SECOND,
        callerId: String = LIFECYCLE_CALLER,
        block: (Answer, EntityResponse) -> Unit,
    ) = lifecycleRun { l ->
        l.env.withEntity { _, entity ->
            lifecycleReady(l, entity)
            l.people.caller = callerId
            l.people.grant(LIFECYCLE_CALLER, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
            if (confirmerRoles.isNotEmpty()) l.people.grant(LIFECYCLE_SECOND, entity.entityRef, *confirmerRoles)
            block(callGoLive(l.env, entity, confirmer), entity)
        }
    }

    @Test
    fun `probe goLive with secondConfirmationBy equal to the caller is 403 and the state is not LIVE`() =
        confirmation(arrayOf(ElectionRoles.TAX_APPROVER), confirmer = LIFECYCLE_CALLER) { answer, entity ->
            assertThat(answer.status).isEqualTo(403)
            assertThat(storedState(entity)).isNotEqualTo("LIVE")
        }

    @Test
    fun `probe goLive with the confirmer USR-0040 against the caller USR-004 is another person and passes`() =
        confirmation(arrayOf(ElectionRoles.TAX_APPROVER)) { answer, entity ->
            assertThat(answer.status).describedAs(answer.detail).isEqualTo(200)
            assertThat(storedState(entity)).isEqualTo("LIVE")
        }

    @Test
    fun `probe goLive with a confirmer who holds only finance viewer is 403 and the state is not LIVE`() =
        confirmation(arrayOf(LIFECYCLE_FINANCE_VIEWER)) { answer, entity ->
            assertThat(answer.status).isEqualTo(403)
            assertThat(storedState(entity)).isNotEqualTo("LIVE")
        }

    @Test
    fun `probe goLive with a confirmer who holds only tax approver is accepted`() =
        confirmation(arrayOf(ElectionRoles.TAX_APPROVER)) { answer, entity ->
            assertThat(answer.status).isEqualTo(200)
            assertThat(storedState(entity)).isEqualTo("LIVE")
        }

    private companion object {
        const val FINANCE_CALLER = "identity:USR-020"
    }
}
