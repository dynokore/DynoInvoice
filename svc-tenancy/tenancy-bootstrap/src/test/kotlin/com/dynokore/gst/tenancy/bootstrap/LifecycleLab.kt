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

internal const val LIFECYCLE_CALLER = "identity:USR-004"
internal const val LIFECYCLE_SECOND = "identity:USR-0040"
internal const val LIFECYCLE_FINANCE_VIEWER = "finance.viewer"
internal const val PROFILE_CHANGED = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"

/** What a call answered: the status, the catalogue code and the detail the caller may be told. */
internal class Answer(
    val status: Int,
    val code: String? = null,
    val detail: String? = null,
    val body: EntityResponse? = null,
)

private val json = JsonMapper.builder().build()

internal class Ports {
    @Volatile var uncovered: List<String> = emptyList()

    @Volatile var unsubmitted: Int = 0
}

internal class Lab(
    val env: ProfileEnv,
    val e: CertificationEnv,
    val elections: ElectionEnv,
    val ports: Ports,
) {
    val people: TestPeople = e.people
}

internal fun primaryBean() = BeanDefinitionCustomizer { it.isPrimary = true }

internal fun lifecycleRun(block: (Lab) -> Unit) {
    val log = TopicLog()
    val ports = Ports()
    BoundaryFixture
        .runner()
        .withBean(EventLogReader::class.java, { log })
        .withBean(TriggerCoverage::class.java, { TriggerCoverage { ports.uncovered } }, primaryBean())
        .withBean(UnsubmittedDocuments::class.java, {
            UnsubmittedDocuments {
                _,
                _,
                ->
                ports.unsubmitted
            }
        }, primaryBean())
        .run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(Lab(env, CertificationEnv(env, log), ElectionEnv(env), ports))
        }
}

/** The service as production binds it: profile `prod`, no producer for the trigger coverage or the documents count. */
internal fun lifecycleProd(block: (ProfileEnv) -> Unit) {
    BoundaryFixture
        .runner()
        .withPropertyValues("spring.profiles.active=prod")
        .run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
}

internal fun lifecycleCall(block: () -> ResponseEntity<EntityResponse>): Answer =
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

internal fun callGoLive(
    env: ProfileEnv,
    entity: EntityResponse,
    confirmer: String = APPROVER,
) = lifecycleCall { env.api.goLive(entity.entityRef, UUID.randomUUID(), GoLiveRequest(confirmer)) }

internal fun callSuspend(
    env: ProfileEnv,
    entity: EntityResponse,
) = lifecycleCall { env.api.suspendEntity(entity.entityRef, UUID.randomUUID(), SuspendEntityRequest("pause")) }

internal fun callDeregister(
    env: ProfileEnv,
    entity: EntityResponse,
    end: String = "2026-08-15",
    override: UnsubmittedOverrideAcknowledgement? = null,
) = lifecycleCall {
    env.api.deregisterEntity(entity.entityRef, UUID.randomUUID(), DeregisterEntityRequest(day(end), override))
}

/** The people of the elections, all four elections met or exempt, and a PASSED certification; the caller is ADMIN. */
internal fun lifecycleReady(
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

internal fun storedState(entity: EntityResponse): String =
    BoundaryFixture.asSuperuser { c ->
        c.prepareStatement("select lifecycle_state from tenancy.entity where ref = ?").use { s ->
            s.setString(1, entity.entityRef)
            s.executeQuery().use {
                it.next()
                it.getString(1)
            }
        }
    }

internal fun outboxRows(
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

internal fun profileEventRows(entity: EntityResponse) = outboxRows(entity, PROFILE_CHANGED)

internal fun structureEventRows(entity: EntityResponse) = outboxRows(entity, "%OrganisationStructureChanged%")

internal fun overrideRows(entity: EntityResponse): List<List<String>> =
    BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "select acknowledged_by, statement, recorded_on::text from tenancy.unsubmitted_override " +
                    "where entity_ref = ?",
            ).use { s ->
                s.setString(1, entity.entityRef)
                s.executeQuery().use { rs ->
                    generateSequence {
                        if (rs.next()) {
                            listOf(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                            )
                        } else {
                            null
                        }
                    }.toList()
                }
            }
    }

internal fun lifecycleRegistered(
    l: Lab,
    entity: EntityResponse,
) {
    val period = PeriodEnv(l.env)
    period.register(entity, "2026-01-01")
    period.change(entity, Freq.QUARTERLY, "2026-01-01")
    l.people.caller = ADMIN
}
