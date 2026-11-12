package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AcknowledgementLocator.Companion.ACKNOWLEDGEMENT_LOCATOR
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AppliedRecord.Companion.APPLIED_RECORD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SealedPayload.Companion.SEALED_PAYLOAD
import com.dynokore.gst.evidence.application.lookup.ChainCursor
import com.dynokore.gst.evidence.application.lookup.ChainItem
import com.dynokore.gst.evidence.application.lookup.ChainItemKind
import com.dynokore.gst.evidence.application.lookup.ChainRecordReader
import com.dynokore.gst.evidence.application.lookup.ChainRecords
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.EvidenceRecord
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID

/**
 * The PAYLOAD and ACKNOWLEDGEMENT items of one version (INV-REQ-691, ruling of 2026-10-14). The records are located
 * through `applied_record` by the source event ids of `acknowledgement_locator` and `sealed_payload`, then read from
 * the chain with [EvidenceLedger.findByEntityAndPeriod]; no chain table is scanned by content. A link without its
 * `applied_record` row, or without its record in the chain, is [ChainRecords.Incomplete], never skipped.
 */
class JooqChainRecordReader(
    private val dsl: DSLContext,
    private val ledgers: Map<RecordKind, EvidenceLedger>,
) : ChainRecordReader {
    private class Located(
        val kind: RecordKind,
        val entityRef: String,
        val periodRef: String,
        val seq: Long,
        val appliedAt: Instant,
    )

    override fun read(
        tenant: TenantId,
        ackId: String,
        docUuid: UUID,
        version: Int,
    ): ChainRecords {
        val ackEvents = ackEvents(tenant, ackId, docUuid, version)
        val payloadEvents = payloadEvents(tenant, docUuid, version)
        val events = ackEvents + payloadEvents
        val applied = applied(tenant, events)
        val linked = ackEvents.isNotEmpty() && events.all { it in applied }
        val acks =
            if (linked) {
                items(tenant, ackId, docUuid, version, ChainItemKind.ACKNOWLEDGEMENT, ackEvents.map(applied::getValue))
            } else {
                null
            }
        val payloads =
            if (acks != null) {
                items(tenant, ackId, docUuid, version, ChainItemKind.PAYLOAD, payloadEvents.map(applied::getValue))
            } else {
                null
            }
        return if (acks == null || payloads == null) {
            ChainRecords.Incomplete
        } else {
            ChainRecords.Complete(
                (acks + payloads).sortedBy { it.key }.mapIndexed { i, item -> item.copy(sequence = i + 1L) },
            )
        }
    }

    private fun ackEvents(
        tenant: TenantId,
        ackId: String,
        docUuid: UUID,
        version: Int,
    ): List<String> =
        dsl
            .select(ACKNOWLEDGEMENT_LOCATOR.SOURCE_EVENT_ID)
            .from(ACKNOWLEDGEMENT_LOCATOR)
            .where(ACKNOWLEDGEMENT_LOCATOR.TENANT_ID.eq(tenant.value))
            .and(ACKNOWLEDGEMENT_LOCATOR.ACK_ID.eq(ackId))
            .and(ACKNOWLEDGEMENT_LOCATOR.DOC_UUID.eq(docUuid))
            .and(ACKNOWLEDGEMENT_LOCATOR.VERSION.eq(version))
            .fetch(ACKNOWLEDGEMENT_LOCATOR.SOURCE_EVENT_ID)
            .filterNotNull()

    private fun payloadEvents(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): List<String> =
        dsl
            .select(SEALED_PAYLOAD.SOURCE_EVENT_ID)
            .from(SEALED_PAYLOAD)
            .where(SEALED_PAYLOAD.TENANT_ID.eq(tenant.value))
            .and(SEALED_PAYLOAD.DOC_UUID.eq(docUuid))
            .and(SEALED_PAYLOAD.VERSION.eq(version))
            .fetch(SEALED_PAYLOAD.SOURCE_EVENT_ID)
            .filterNotNull()

    private fun applied(
        tenant: TenantId,
        events: List<String>,
    ): Map<String, Located> =
        dsl
            .select(
                APPLIED_RECORD.SOURCE_EVENT_ID,
                APPLIED_RECORD.KIND,
                APPLIED_RECORD.ENTITY_REF,
                APPLIED_RECORD.PERIOD_REF,
                APPLIED_RECORD.SEQ,
                APPLIED_RECORD.APPLIED_AT,
            ).from(APPLIED_RECORD)
            .where(APPLIED_RECORD.TENANT_ID.eq(tenant.value))
            .and(APPLIED_RECORD.SOURCE_EVENT_ID.`in`(events))
            .fetch()
            .associate {
                checkNotNull(it[APPLIED_RECORD.SOURCE_EVENT_ID]) to
                    Located(
                        RecordKind.entries.single { k -> k.table == it[APPLIED_RECORD.KIND] },
                        checkNotNull(it[APPLIED_RECORD.ENTITY_REF]),
                        checkNotNull(it[APPLIED_RECORD.PERIOD_REF]),
                        checkNotNull(it[APPLIED_RECORD.SEQ]),
                        checkNotNull(it[APPLIED_RECORD.APPLIED_AT]).toInstant(),
                    )
            }

    /** The items of [kind] for the located rows, or null when a row is of another chain kind or its record is gone. */
    @Suppress("LongParameterList") // one version's identity plus the located rows
    private fun items(
        tenant: TenantId,
        ackId: String,
        docUuid: UUID,
        version: Int,
        kind: ChainItemKind,
        located: List<Located>,
    ): List<ChainItem>? {
        val expected =
            if (kind == ChainItemKind.ACKNOWLEDGEMENT) RecordKind.ACKNOWLEDGEMENT else RecordKind.EVIDENCE_PAYLOAD
        val items =
            if (located.any { it.kind != expected }) {
                emptyList()
            } else {
                located.groupBy { it.entityRef to it.periodRef }.flatMap { (chain, rows) ->
                    val bySeq = rows.associateBy { it.seq }
                    checkNotNull(ledgers[expected])
                        .findByEntityAndPeriod(tenant, EntityRef(chain.first), PeriodRef(chain.second))
                        .mapNotNull { rec -> bySeq[rec.sequence]?.let { item(kind, ackId, docUuid, version, it, rec) } }
                }
            }
        return if (items.size == located.size) items else null
    }

    @Suppress("LongParameterList") // one item's identity and its two sources
    private fun item(
        kind: ChainItemKind,
        ackId: String,
        docUuid: UUID,
        version: Int,
        row: Located,
        record: EvidenceRecord,
    ): ChainItem {
        val reference = if (kind == ChainItemKind.ACKNOWLEDGEMENT) ackId else "payload/$docUuid/v$version/${row.seq}"
        return ChainItem(kind, record.recordedAt, reference, 0, ChainCursor(row.appliedAt, kind, row.seq))
    }
}
