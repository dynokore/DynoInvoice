package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.metering.Metering
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.format.DateTimeParseException

/** Reads the payload of a consumed metering event; a payload that breaks the contract goes to the dead letter. */
private class Payload(
    dataJson: String,
) {
    private val root: JsonNode =
        try {
            JSON.readTree(dataJson)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    fun text(name: String): String =
        root
            .get(name)
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeUnless { it.isBlank() }
            ?: throw ContractViolation("$name is not a non-blank string")

    fun instant(name: String): Instant =
        try {
            Instant.parse(text(name))
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$name is not a UTC date-time", e)
        }

    fun long(name: String): Long =
        root
            .get(name)
            ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.asLong()
            ?.takeIf { it >= 0 }
            ?: throw ContractViolation("$name is not a non-negative integer")

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * `SubmissionAcknowledged` (INV-REQ-878, INV-Q-9815 A): counts one document (payload `version` 1) or one resubmission
 * (above 1) for the envelope entity in the UTC month of the payload `timestamp`. A test-mode event counts nothing (the
 * envelope `testMode`, INV-Q-12635 A); a repeated envelope id is dropped by the inbox of the consumer.
 */
class SubmissionAcknowledgedHandler(
    private val metering: Metering,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != TenancyEventCatalogue.SUBMISSION_ACKNOWLEDGED_TYPE) return HandleOutcome.IGNORED
        val payload = Payload(event.dataJson)
        val timestamp = payload.instant("timestamp")
        val version = payload.long("version")
        if (version < 1) throw ContractViolation("version is below 1")
        if (!event.testMode) metering.acknowledged(event.entity.value, timestamp, version.toInt())
        return if (event.testMode) HandleOutcome.IGNORED else HandleOutcome.APPLIED
    }
}

/**
 * `ArchiveVolumeMeasured` and `TenantWideArchiveVolumeMeasured` (INV-REQ-878, INV-Q-9829 A, INV-Q-14069 A): stored for
 * the tenant of `tenantRef` only when it is the envelope tenant (fails closed); the latest `measuredAt` is kept.
 */
class ArchiveVolumeHandler(
    private val metering: Metering,
    private val type: String,
    private val perEntity: Boolean,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != type) return HandleOutcome.IGNORED
        val payload = Payload(event.dataJson)
        val tenant =
            runCatching { TenantRefs.idOf(payload.text("tenantRef")) }
                .getOrElse { throw ContractViolation("tenantRef is not a usable tenant reference") }
        val entityRef = if (perEntity) payload.text("entityRef") else null
        if (entityRef == EntityRef.TENANT_WIDE.value) {
            throw ContractViolation("entityRef is the reserved tenant-wide value")
        }
        val bytes = payload.long("bytes")
        val measuredAt = payload.instant("measuredAt")
        if (tenant == event.tenant) metering.measured(entityRef, bytes, measuredAt)
        return if (tenant == event.tenant) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
