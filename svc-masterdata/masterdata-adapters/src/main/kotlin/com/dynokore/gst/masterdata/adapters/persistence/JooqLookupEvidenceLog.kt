package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.LOOKUP_RECORD
import com.dynokore.gst.masterdata.application.registry.LookupEvidenceLog
import com.dynokore.gst.masterdata.domain.registry.LookupEvidence
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-REQ-802 b2, INV-Q-9645 A. Stores one lookup (queried value, response, time) in the lookup record and publishes
 * `EvidenceArtefactRecorded` through service-runtime's recorder in the caller's transaction: type `REGISTER_LOOKUP`,
 * `reference` the key of the record, `hash` the SHA-256 of the stored bytes. The link to the submission rides on the
 * envelope: its correlation is the one of the call, and the same text is kept on the record.
 */
class JooqLookupEvidenceLog(
    private val dsl: DSLContext,
    private val recorder: EvidenceArtefactRecorder,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : LookupEvidenceLog {
    override fun record(
        entity: String,
        evidence: LookupEvidence,
        degradation: String?,
    ): String {
        val id = ids.next(clock.instant())
        val key = "LKP-$id"
        val bytes = evidence.bytes
        val artefact = EvidenceArtefact.of(EvidenceArtefactType.REGISTER_LOOKUP, key, bytes.toByteArray(Charsets.UTF_8))
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(LOOKUP_RECORD)
            .set(LOOKUP_RECORD.ID, id)
            .set(LOOKUP_RECORD.RECORD_KEY, key)
            .set(LOOKUP_RECORD.ENTITY_REF, entity)
            .set(LOOKUP_RECORD.KIND, evidence.kind.name)
            .set(LOOKUP_RECORD.QUERIED, evidence.queried)
            .set(LOOKUP_RECORD.BYTES, bytes)
            .set(LOOKUP_RECORD.HASH, artefact.hash)
            .set(LOOKUP_RECORD.LOOKED_UP_AT, evidence.at.atOffset(ZoneOffset.UTC))
            .set(LOOKUP_RECORD.DEGRADATION, degradation)
            .set(LOOKUP_RECORD.CORRELATION_ID, correlation)
            .set(LOOKUP_RECORD.CREATED_AT, now)
            .set(LOOKUP_RECORD.UPDATED_AT, now)
            .set(LOOKUP_RECORD.VERSION, 0L)
            .execute()
        recorder.record(
            artefact,
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity),
                docUuid = null,
                transactionDate = null,
                aggregateKey = key,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
        return key
    }
}
