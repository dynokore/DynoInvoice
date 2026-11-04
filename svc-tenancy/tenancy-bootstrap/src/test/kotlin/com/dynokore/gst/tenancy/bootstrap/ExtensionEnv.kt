package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.application.period.StoredPeriod
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionRequest
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionRequestScope
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionResponse
import com.dynokore.gst.tenancy.contract.model.PeriodRecord
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

internal typealias Req = PeriodExtensionRequest
internal typealias Scope = PeriodExtensionRequestScope
internal typealias Tgt = PeriodExtensionRequest.Target
internal typealias ScopeKind = PeriodExtensionRequestScope.Kind

internal val periodJson: JsonMapper = JsonMapper.builder().build()

/** Runs [block] on a fresh tenant with one entity, over the real application context (test profile). */
internal fun runPeriods(block: (PeriodEnv, EntityResponse) -> Unit) {
    BoundaryFixture.runner().run { context ->
        context.startupFailure?.let { throw it }
        val env = ProfileEnv(context)
        env.withEntity { _, entity -> block(PeriodEnv(env), entity) }
    }
}

/** A stored period record with an explicit reference, as the generator would have stored it. */
internal fun record(
    ref: String,
    start: String,
    end: String,
    due: String,
) = StoredPeriod(ref, day(start), day(end), "F5", day(due), emptySet())

/** The tax.approver of the entity calls recordExtension with the request's fields. */
internal fun PeriodEnv.extend(
    entity: EntityResponse,
    periodRef: String,
    request: PeriodExtensionRequest,
): ResponseEntity<PeriodExtensionResponse> =
    periodsApi.recordExtension(entity.entityRef, periodRef, UUID.randomUUID(), request)

internal fun extension(
    newDueDate: String,
    iras: String = "IRAS-EXT-2026-118",
    evidence: String = "DOC-9911",
) = PeriodExtensionRequest(day(newDueDate), iras, evidence)

/** The events of the type the outbox has appended so far, as parsed payloads. */
internal fun PeriodEnv.events(type: String): List<JsonNode> =
    env.captured.envelopes
        .filter { it.type == type }
        .map { periodJson.readTree(it.dataJson) }

internal const val PERIOD_EXTENDED = "com.dynokore.gst.tenancy.PeriodExtended.v1"
internal const val PERIOD_DUE_DATE_CHANGED = "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1"

/** The records of the entity through the operation, as the contract publishes them. */
internal fun PeriodEnv.listed(entity: EntityResponse): List<PeriodRecord> =
    periodsApi.listPeriodRecords(entity.entityRef, 100, null).body!!.items

/** The rows of a table of the entity as JSON text (every column), in a fixed order. */
internal fun PeriodEnv.table(
    entity: EntityResponse,
    table: String,
    order: String,
): List<String> {
    val id = entityId(entity)
    return BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "select row_to_json(t)::text from tenancy.$table t where entity_id = ? order by $order",
            ).use { s ->
                s.setObject(1, id)
                s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
    }
}

internal fun refusalOf(block: () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (e: ApiRefusal) {
        e
    } catch (e: com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal) {
        e
    }

/** An envelope of [type] for [tenant] carrying [data], as the inbox consumer hands it to a handler. */
internal fun testEnvelope(
    type: String,
    tenant: com.dynokore.platform.tenancykernel.TenantId,
    data: String,
    id: String = UUID.randomUUID().toString(),
) = com.dynokore.platform.outboxandqueue.Envelope(
    com.dynokore.platform.outboxandqueue
        .EventId(id),
    "/gst/test",
    type,
    "subject",
    java.time.Instant.now(),
    tenant,
    com.dynokore.platform.outboxandqueue
        .EntityRef("subject"),
    null,
    null,
    null,
    "test-0",
    com.dynokore.platform.outboxandqueue
        .CorrelationId("corr-${UUID.randomUUID()}"),
    com.dynokore.platform.outboxandqueue
        .CausationId("cause-${UUID.randomUUID()}"),
    data,
)

/** Delivers [data] to [handler] in the tenant's transaction, as the inbox consumer does; the outcome name. */
internal fun PeriodEnv.deliver(
    tenant: com.dynokore.platform.tenancykernel.TenantId,
    handler: com.dynokore.platform.outboxandqueue.EventHandler,
    type: String,
    data: String,
    id: String = UUID.randomUUID().toString(),
): String = env.inTenant(tenant) { env.inTx { handler.handle(testEnvelope(type, tenant, data, id)).name } }
