package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.application.offboarding.ExportOrder
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.contract.model.DeregisterEntityRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GoLiveRequest
import com.dynokore.gst.tenancy.contract.model.SuspendEntityRequest
import com.dynokore.gst.tenancy.contract.model.UnsubmittedOverrideAcknowledgement
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.GoLiveGate
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
    val clock: Clock,
) {
    /** The business date the use cases take from this same clock (the Singapore calendar day, INV-Q-16113). */
    val today: LocalDate get() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Singapore")))

    val people: TestPeople = e.people
}

/**
 * A clock that moves one millisecond on every reading, so that two records written in one test never share a
 * `recorded_at` (the latest certification is ordered by it; a tie fell to a random id and made INV-AC-1064 flaky).
 */
internal class TickingClock(
    start: Instant,
) : Clock() {
    private val next =
        java.util.concurrent.atomic
            .AtomicLong(start.toEpochMilli())

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)

    override fun instant(): Instant = Instant.ofEpochMilli(next.getAndIncrement())
}

internal fun primaryBean() = BeanDefinitionCustomizer { it.isPrimary = true }

internal fun lifecycleRun(block: (Lab) -> Unit) {
    val log = TopicLog()
    val ports = Ports()
    val clock = TickingClock(Instant.now())
    BoundaryFixture
        .runner()
        .withBean(Clock::class.java, { clock }, primaryBean())
        .withBean(EventLogReader::class.java, { log })
        .withBean(TriggerCoverage::class.java, { TriggerCoverage { ports.uncovered } }, primaryBean())
        .withBean(TenantExport::class.java, {
            TenantExport { order: ExportOrder ->
                ExportManifest("EXP-${order.offboardingRef}", ExportState.READY, ExportContent.entries)
            }
        }, primaryBean())
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
            block(Lab(env, CertificationEnv(env, log), ElectionEnv(env), ports, clock))
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
    openTenant: Boolean = true,
) {
    // INV-Q-8097 B: goLive needs an OPEN tenant. The row is set directly so that these fixtures need not nest the
    // platform binding of openTenant inside the tenant binding they run in; INV-AC-8139 calls openTenant itself.
    if (openTenant) setTenantState(l.env.tenants.current(), "OPEN")
    l.elections.usualPeople(entity)
    l.elections.mode(entity, com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest.Mode.RELAY)
    l.elections.elect(entity, "INVOICE_DATE")
    l.elections.facts.set(entity.entityRef, "recordsPurchases", FactAnswer.FALSE)
    l.elections.facts.set(entity.entityRef, "hasForeignCurrencyDocuments", FactAnswer.FALSE)
    l.e.passed(entity)
    l.people.caller = ADMIN
}

/** Writes the stored tenant state text directly as the database owner (fixtures and look-alike probes only). */
internal fun setTenantState(
    tenant: TenantId,
    state: String,
) = BoundaryFixture.asSuperuser { c ->
    c.prepareStatement("update tenancy.tenant set state = ? where tenant_id = ?").use { s ->
        s.setString(1, state)
        s.setObject(2, tenant.value)
        s.executeUpdate()
    }
}

internal fun storedTenantState(tenant: TenantId): String =
    BoundaryFixture.asSuperuser { c ->
        c.prepareStatement("select state from tenancy.tenant where tenant_id = ?").use { s ->
            s.setObject(1, tenant.value)
            s.executeQuery().use {
                it.next()
                it.getString(1)
            }
        }
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

/** The audit entries of the entity (the audit-log events staged in the outbox) whose action is [action]. */
internal fun auditRows(
    entity: EntityResponse,
    action: String,
): List<JsonNode> =
    BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "select data::text from tenancy.outbox where topic = 'gst.platform.audit-entry-recorded.v1' " +
                    "and entity_ref = ? and data::text like ? order by seq",
            ).use { s ->
                s.setString(1, entity.entityRef)
                s.setString(2, "%$action%")
                s.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) json.readTree(rs.getString(1)) else null }.toList()
                }
            }
    }

internal fun callSuspendWith(
    env: ProfileEnv,
    entity: EntityResponse,
    reason: String,
) = lifecycleCall { env.api.suspendEntity(entity.entityRef, UUID.randomUUID(), SuspendEntityRequest(reason)) }
