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

private const val CALLER = "identity:USR-004"
private const val SECOND = "identity:USR-0040"
private const val FINANCE_VIEWER = "finance.viewer"
private const val PROFILE_CHANGED = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"

/** What a call answered: the status, the catalogue code and the detail the caller may be told. */
private class Answer(
    val status: Int,
    val code: String? = null,
    val detail: String? = null,
    val body: EntityResponse? = null,
)

/**
 * goLive, suspendEntity and deregisterEntity through the controller of the real composition over PostgreSQL (INV-T-4931
 * on the use cases of INV-T-16414): the response status, the refusal detail, the stored `lifecycle_state` and the
 * outbox rows of the call, compared by equality. The trigger-coverage and unsubmitted-documents ports are stubs (their
 * producers come in Wave 3, coordinator ruling INV-Q-4900).
 */
class EntityLifecycleHttpTest {
    private val json = JsonMapper.builder().build()

    private class Ports {
        @Volatile var uncovered: List<String> = emptyList()

        @Volatile var unsubmitted: Int = 0
    }

    private class Lab(
        val env: ProfileEnv,
        val e: CertificationEnv,
        val elections: ElectionEnv,
        val ports: Ports,
    ) {
        val people: TestPeople = e.people
    }

    private fun primary() = BeanDefinitionCustomizer { it.isPrimary = true }

    private fun run(block: (Lab) -> Unit) {
        val log = TopicLog()
        val ports = Ports()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .withBean(TriggerCoverage::class.java, { TriggerCoverage { ports.uncovered } }, primary())
            .withBean(UnsubmittedDocuments::class.java, { UnsubmittedDocuments { _, _ -> ports.unsubmitted } }, primary())
            .run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                block(Lab(env, CertificationEnv(env, log), ElectionEnv(env), ports))
            }
    }

    /** The service as production binds it: profile `prod`, no producer for the trigger coverage or the documents count. */
    private fun runProd(block: (ProfileEnv) -> Unit) {
        BoundaryFixture
            .runner()
            .withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                context.startupFailure?.let { throw it }
                block(ProfileEnv(context))
            }
    }

    private fun call(block: () -> ResponseEntity<EntityResponse>): Answer =
        try {
            block().let { Answer(it.statusCode.value(), body = it.body) }
        } catch (refusal: ApiRefusal) {
            Answer(Problems.render(refusal.code, "corr-test", refusal.detail).status, refusal.code.code, refusal.detail)
        } catch (refusal: ConstraintRefusal) {
            Answer(422, "POL-014", refusal.detail)
        } catch (refusal: TenancyRefusal) {
            Answer(
                Problems.renderTenancy(refusal.code, "corr-test", refusal.detail).status,
                refusal.code.code,
                refusal.detail,
            )
        }

    private fun goLive(
        env: ProfileEnv,
        entity: EntityResponse,
        confirmer: String = APPROVER,
    ) = call { env.api.goLive(entity.entityRef, UUID.randomUUID(), GoLiveRequest(confirmer)) }

    private fun suspend(
        env: ProfileEnv,
        entity: EntityResponse,
    ) = call { env.api.suspendEntity(entity.entityRef, UUID.randomUUID(), SuspendEntityRequest("pause")) }

    private fun deregister(
        env: ProfileEnv,
        entity: EntityResponse,
        end: String = "2026-08-15",
        override: UnsubmittedOverrideAcknowledgement? = null,
    ) = call {
        env.api.deregisterEntity(entity.entityRef, UUID.randomUUID(), DeregisterEntityRequest(day(end), override))
    }

    /** The people of the elections, all four elections met or exempt, and a PASSED certification; the caller is ADMIN. */
    private fun ready(
        l: Lab,
        entity: EntityResponse,
    ) {
        l.elections.usualPeople(entity)
        l.elections.mode(entity, com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest.Mode.RELAY)
        l.elections.elect(entity, "INVOICE_DATE")
        l.elections.facts.set(entity.entityRef, "recordsPurchases", FactAnswer.FALSE)
        l.elections.facts.set(entity.entityRef, "hasForeignCurrencyDocuments", FactAnswer.FALSE)
        l.e.passed(entity)
        l.people.caller = ADMIN
    }

    private fun stored(entity: EntityResponse): String =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select lifecycle_state from tenancy.entity where ref = ?").use { s ->
                s.setString(1, entity.entityRef)
                s.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun outbox(
        entity: EntityResponse,
        type: String,
    ): List<JsonNode> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement("select data::text from tenancy.outbox where type like ? and entity_ref = ? order by seq")
                .use { s ->
                    s.setString(1, type)
                    s.setString(2, entity.entityRef)
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) json.readTree(rs.getString(1)) else null }.toList()
                    }
                }
        }

    private fun profileEvents(entity: EntityResponse) = outbox(entity, PROFILE_CHANGED)

    private fun structureEvents(entity: EntityResponse) = outbox(entity, "%OrganisationStructureChanged%")

    private fun overrides(entity: EntityResponse): List<List<String>> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select acknowledged_by, statement, recorded_on::text from tenancy.unsubmitted_override " +
                        "where entity_ref = ?",
                ).use { s ->
                    s.setString(1, entity.entityRef)
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
                            .toList()
                    }
                }
        }

    private fun registered(
        l: Lab,
        entity: EntityResponse,
    ) {
        val period = PeriodEnv(l.env)
        period.register(entity, "2026-01-01")
        period.change(entity, Freq.QUARTERLY, "2026-01-01")
        l.people.caller = ADMIN
    }

    // ---- goLive -----------------------------------------------------------------------------------------------

    @Test
    fun `probe goLive with the trigger-coverage port unbound in profile prod starts and is refused naming it`() {
        runProd { env ->
            env.withEntity { _, entity ->
                val people = env.context.getBean(TestPeople::class.java)
                people.caller = ADMIN
                people.grant(ADMIN, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
                people.grant(APPROVER, entity.entityRef, ElectionRoles.TAX_APPROVER)
                val answer = goLive(env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains(GoLiveGate.COVERAGE_UNKNOWN)
                assertThat(stored(entity)).isNotEqualTo("LIVE")
                assertThat(profileEvents(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe deregisterEntity with the documents count unbound in profile prod is 422 POL-014 naming the input`() {
        runProd { env ->
            env.withEntity { _, entity ->
                val people = env.context.getBean(TestPeople::class.java)
                people.caller = ADMIN
                people.grant(ADMIN, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
                val before = stored(entity)
                val answer = deregister(env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("unsubmitted-documents-count")
                assertThat(stored(entity)).isEqualTo(before)
                assertThat(profileEvents(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe goLive with no certification record is refused naming the sandbox certification`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                BoundaryFixture.asSuperuser { c ->
                    c
                        .prepareStatement(
                            "delete from tenancy.sandbox_certification where entity_id = (select id from tenancy.entity where ref = ?)",
                        ).use {
                            it.setString(1, entity.entityRef)
                            it.executeUpdate()
                        }
                }
                val before = stored(entity)
                val rows = profileEvents(entity).size
                val answer = goLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("sandbox certification")
                assertThat(stored(entity)).isEqualTo(before)
                assertThat(profileEvents(entity).size).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `INV-AC-1064 goLive whose latest certification is FAILED with s1 and s2 is refused naming both`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                l.e.actAs(CERTIFIER, entity, RECORD_KEY)
                l.e.certify(entity, CertificationOutcome.FAILED, listOf("s1", "s2"))
                l.people.caller = ADMIN
                val before = stored(entity)
                val rows = profileEvents(entity).size
                val answer = goLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("s1").contains("s2")
                assertThat(stored(entity)).isEqualTo(before)
                assertThat(stored(entity)).isNotEqualTo("LIVE")
                assertThat(profileEvents(entity).size).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `probe goLive with a trigger class not covered is refused naming it`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                l.ports.uncovered = listOf("CREDIT_NOTE")
                val answer = goLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(answer.detail).contains("CREDIT_NOTE")
                assertThat(stored(entity)).isNotEqualTo("LIVE")
            }
        }
    }

    @Test
    fun `probe goLive whose latest record is PASSED and all items met is LIVE with one EntityProfileChanged`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                val before = profileEvents(entity).size
                val answer = goLive(l.env, entity)
                assertThat(answer.status).describedAs(answer.detail).isEqualTo(200)
                assertThat(answer.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.LIVE)
                assertThat(stored(entity)).isEqualTo("LIVE")
                val rows = profileEvents(entity)
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
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                val before = structureEvents(entity).size
                assertThat(goLive(l.env, entity).status).isEqualTo(200)
                assertThat(structureEvents(entity).size - before).isEqualTo(0)
            }
        }
    }

    @Test
    fun `probe goLive repeated keeps the state LIVE and writes no second outbox row`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                assertThat(goLive(l.env, entity).status).isEqualTo(200)
                val rows = profileEvents(entity).size
                val again = goLive(l.env, entity)
                assertThat(again.status).isEqualTo(200)
                assertThat(again.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.LIVE)
                assertThat(stored(entity)).isEqualTo("LIVE")
                assertThat(profileEvents(entity).size).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `probe goLive by a caller without entity-config manage is 403 and changes nothing`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                l.people.caller = FINANCE_CALLER
                l.people.grant(FINANCE_CALLER, entity.entityRef, FINANCE_VIEWER)
                val answer = goLive(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(403, "SRT-004"))
                assertThat(stored(entity)).isNotEqualTo("LIVE")
            }
        }
    }

    // ---- second confirmation of goLive ---------------------------------------------------------------------------

    private fun confirmation(
        confirmerRoles: Array<String>,
        confirmer: String = SECOND,
        callerId: String = CALLER,
        block: (Answer, EntityResponse) -> Unit,
    ) = run { l ->
        l.env.withEntity { _, entity ->
            ready(l, entity)
            l.people.caller = callerId
            l.people.grant(CALLER, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
            if (confirmerRoles.isNotEmpty()) l.people.grant(SECOND, entity.entityRef, *confirmerRoles)
            block(goLive(l.env, entity, confirmer), entity)
        }
    }

    @Test
    fun `probe goLive with secondConfirmationBy equal to the caller is 403 and the state is not LIVE`() =
        confirmation(arrayOf(ElectionRoles.TAX_APPROVER), confirmer = CALLER) { answer, entity ->
            assertThat(answer.status).isEqualTo(403)
            assertThat(stored(entity)).isNotEqualTo("LIVE")
        }

    @Test
    fun `probe goLive with the confirmer USR-0040 against the caller USR-004 is another person and passes`() =
        confirmation(arrayOf(ElectionRoles.TAX_APPROVER)) { answer, entity ->
            assertThat(answer.status).describedAs(answer.detail).isEqualTo(200)
            assertThat(stored(entity)).isEqualTo("LIVE")
        }

    @Test
    fun `probe goLive with a confirmer who holds only finance viewer is 403 and the state is not LIVE`() =
        confirmation(arrayOf(FINANCE_VIEWER)) { answer, entity ->
            assertThat(answer.status).isEqualTo(403)
            assertThat(stored(entity)).isNotEqualTo("LIVE")
        }

    @Test
    fun `probe goLive with a confirmer who holds only tax approver is accepted`() =
        confirmation(arrayOf(ElectionRoles.TAX_APPROVER)) { answer, entity ->
            assertThat(answer.status).isEqualTo(200)
            assertThat(stored(entity)).isEqualTo("LIVE")
        }

    // ---- suspendEntity ---------------------------------------------------------------------------------------------

    @Test
    fun `probe suspendEntity sets SUSPENDED, not LIVE, and retracts nothing`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                assertThat(goLive(l.env, entity).status).isEqualTo(200)
                val certifications = l.e.rows(entity)
                val others = outbox(entity, "%").size - profileEvents(entity).size
                val structure = structureEvents(entity).size
                val answer = suspend(l.env, entity)
                assertThat(answer.status).isEqualTo(200)
                assertThat(answer.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.SUSPENDED)
                assertThat(stored(entity)).isEqualTo("SUSPENDED")
                assertThat(stored(entity)).isNotEqualTo("LIVE")
                val change = profileEvents(entity).last()["changedAttributes"]
                assertThat(change[0]["attribute"].asString()).isEqualTo("lifecycleState")
                assertThat(change[0]["newValue"].asString()).isEqualTo("SUSPENDED")
                assertThat(l.e.rows(entity)).isEqualTo(certifications)
                assertThat(outbox(entity, "%").size - profileEvents(entity).size).isEqualTo(others)
                assertThat(structureEvents(entity).size).isEqualTo(structure)
            }
        }
    }

    // ---- deregisterEntity --------------------------------------------------------------------------------------------

    @Test
    fun `probe deregisterEntity with an unsubmitted document and no override is 409 TNC-008 and changes nothing`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                registered(l, entity)
                l.ports.unsubmitted = 2
                val before = stored(entity)
                val rows = profileEvents(entity).size
                val answer = deregister(l.env, entity)
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(409, "TNC-008"))
                assertThat(stored(entity)).isEqualTo(before)
                assertThat(profileEvents(entity).size).isEqualTo(rows)
                assertThat(overrides(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe deregisterEntity with the override is DEREGISTERED and the acknowledgement is recorded`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                registered(l, entity)
                l.ports.unsubmitted = 2
                val rows = profileEvents(entity).size
                val structure = structureEvents(entity).size
                val ack = UnsubmittedOverrideAcknowledgement(ADMIN, "two drafts are abandoned")
                val answer = deregister(l.env, entity, override = ack)
                assertThat(answer.status).describedAs(answer.detail).isEqualTo(200)
                assertThat(answer.body!!.lifecycleState).isEqualTo(EntityResponse.LifecycleState.DEREGISTERED)
                assertThat(stored(entity)).isEqualTo("DEREGISTERED")
                assertThat(overrides(entity)).isEqualTo(listOf(listOf(ADMIN, "two drafts are abandoned", TODAY.toString())))
                assertThat(profileEvents(entity).size - rows).isEqualTo(1)
                assertThat(structureEvents(entity).size).isEqualTo(structure)
            }
        }
    }

    @Test
    fun `probe deregisterEntity publishes registrationEndDate first and lifecycleState second in one event`() {
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                registered(l, entity)
                val rows = profileEvents(entity).size
                assertThat(deregister(l.env, entity).status).isEqualTo(200)
                val events = profileEvents(entity)
                assertThat(events.size - rows).isEqualTo(1)
                val event = events.last()
                val changes = event["changedAttributes"]
                assertThat(changes.size()).isEqualTo(2)
                assertThat(listOf(changes[0]["attribute"].asString(), changes[0]["newValue"].asString()))
                    .isEqualTo(listOf("registrationEndDate", "2026-08-15"))
                assertThat(listOf(changes[1]["attribute"].asString(), changes[1]["newValue"].asString()))
                    .isEqualTo(listOf("lifecycleState", "DEREGISTERED"))
                assertThat(event["effectiveDate"].asString()).isEqualTo(TODAY.toString())
                val profile = event["node"]["profile"].values().filter { it["attribute"].asString() == "registrationEndDate" }
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
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                registered(l, entity)
                assertThat(deregister(l.env, entity).status).isEqualTo(200)
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
        run { l ->
            l.env.withEntity { _, entity ->
                ready(l, entity)
                l.ports.unsubmitted = 1
                val before = stored(entity)
                val rows = profileEvents(entity).size
                val answer = deregister(l.env, entity, override = UnsubmittedOverrideAcknowledgement(ADMIN, "abandoned"))
                assertThat(listOf(answer.status, answer.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(stored(entity)).isEqualTo(before)
                assertThat(profileEvents(entity).size).isEqualTo(rows)
                assertThat(overrides(entity)).isEmpty()
            }
        }
    }

    private companion object {
        const val FINANCE_CALLER = "identity:USR-020"
    }
}
