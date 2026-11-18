package com.dynokore.gst.reconciliation.adapters.events

import com.dynokore.gst.reconciliation.application.returns.GstReturnRecordedPublisher
import com.dynokore.gst.reconciliation.application.returns.StoredReturn
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID

/**
 * [GstReturnRecordedPublisher] over the transactional outbox (Richardson): the event is appended in the open
 * transaction of the caller, so a rollback leaves no event (INV-T-24367, INV-AC-24366). `returnVersion` is a JSON
 * string of the decimal digits (the contract type); `irasFilingReference` is a member only when the stored reference
 * is neither null nor blank, never the acknowledgement; `acknowledgementHash` is `sha256:` and the 64 lower-case hex
 * of the SHA-256 of the UTF-8 bytes of the stored acknowledgement.
 */
class OutboxGstReturnRecordedPublisher(
    private val outbox: OutboxWriter,
) : GstReturnRecordedPublisher {
    override fun publish(
        tenant: String,
        entityRef: String,
        periodRef: String,
        stored: StoredReturn,
    ) {
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload(entityRef, periodRef, stored))),
            EventContext(
                tenant = TenantId(UUID.fromString(tenant)),
                entity = EntityRef(entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = "$entityRef:$periodRef",
                aggregateVersion = null,
                correlation = CorrelationId(UUID.randomUUID().toString()),
                causation = CausationId("return-recorded:$entityRef:$periodRef:${stored.recorded.version}"),
            ),
        )
    }

    private fun payload(
        entityRef: String,
        periodRef: String,
        stored: StoredReturn,
    ): String {
        val members = linkedMapOf<String, Any>()
        members["entityRef"] = entityRef
        members["period"] = periodRef
        members["form"] = stored.recorded.form.value
        members["returnVersion"] = stored.recorded.version.toString()
        members["filedAt"] = DateTimeFormatter.ISO_INSTANT.format(stored.recorded.filedAt)
        members["acknowledgementHash"] = hashOf(stored.recorded.filingAcknowledgement)
        stored.filingReference?.takeIf { it.isNotBlank() }?.let { members["irasFilingReference"] = it }
        return MAPPER.writeValueAsString(members)
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.reconciliation.GstReturnRecorded.v1"
        private const val HASH_PREFIX = "sha256:"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()

        fun hashOf(acknowledgement: String): String =
            HASH_PREFIX +
                HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(acknowledgement.toByteArray(Charsets.UTF_8)),
                )
    }
}
