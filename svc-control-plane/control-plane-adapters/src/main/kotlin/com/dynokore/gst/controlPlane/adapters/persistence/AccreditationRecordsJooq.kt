package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.records.AccreditationRecordRecord
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.ACCREDITATION_RECORD
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationRecords
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord
import com.dynokore.gst.controlPlane.domain.accreditation.SandboxAcknowledgement
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Accreditation records in the `controlplane` schema through jOOQ (TPL-11): insert and read only. */
class AccreditationRecordsJooq(
    private val dsl: DSLContext,
) : AccreditationRecords {
    private val mapper = JsonMapper.builder().build()

    override fun latest(): AccreditationRecord? =
        dsl
            .selectFrom(ACCREDITATION_RECORD)
            .orderBy(ACCREDITATION_RECORD.RECORDED_AT.desc(), ACCREDITATION_RECORD.ID.desc())
            .limit(1)
            .fetchOne()
            ?.let(::recordOf)

    override fun find(
        imdaReference: String,
        currentUntil: LocalDate,
    ): AccreditationRecord? =
        dsl
            .selectFrom(ACCREDITATION_RECORD)
            .where(ACCREDITATION_RECORD.IMDA_REFERENCE.eq(imdaReference))
            .and(ACCREDITATION_RECORD.CURRENT_UNTIL.eq(currentUntil))
            .fetchOne()
            ?.let(::recordOf)

    override fun save(record: AccreditationRecord) {
        val acknowledgements = DSL.value(acknowledgementsJson(record)).cast(SQLDataType.JSONB)
        dsl
            .insertInto(ACCREDITATION_RECORD)
            .set(ACCREDITATION_RECORD.ID, UUID.randomUUID())
            .set(ACCREDITATION_RECORD.GRANTED_ON, record.grantedOn)
            .set(ACCREDITATION_RECORD.CURRENT_UNTIL, record.currentUntil)
            .set(ACCREDITATION_RECORD.IMDA_REFERENCE, record.imdaReference)
            .set(
                ACCREDITATION_RECORD.COVERED_SUBMISSION_TYPES,
                record.coveredSubmissionTypes.map { it.name }.toTypedArray(),
            ).set(ACCREDITATION_RECORD.SANDBOX_ACKNOWLEDGEMENTS, acknowledgements)
            .set(ACCREDITATION_RECORD.RECORDED_AT, record.recordedAt.atOffset(ZoneOffset.UTC))
            .set(ACCREDITATION_RECORD.RECORDED_BY, record.recordedBy.value)
            .onConflict(ACCREDITATION_RECORD.IMDA_REFERENCE, ACCREDITATION_RECORD.CURRENT_UNTIL)
            .doNothing()
            .execute()
    }

    private fun acknowledgementsJson(record: AccreditationRecord): String =
        mapper.writeValueAsString(
            record.sandboxAcknowledgements.map {
                linkedMapOf("submissionType" to it.submissionType.name, "acknowledgementId" to it.acknowledgementId)
            },
        )

    private fun recordOf(row: AccreditationRecordRecord) =
        AccreditationRecord(
            checkNotNull(row.grantedOn),
            checkNotNull(row.currentUntil),
            checkNotNull(row.imdaReference),
            checkNotNull(row.coveredSubmissionTypes).map { SubmissionType.valueOf(checkNotNull(it)) },
            mapper.readTree(checkNotNull(row.sandboxAcknowledgements).data()).values().map { node ->
                SandboxAcknowledgement(
                    SubmissionType.valueOf(node.get("submissionType").asString()),
                    node.get("acknowledgementId").asString(),
                )
            },
            checkNotNull(row.recordedAt).toInstant(),
            ActorId(checkNotNull(row.recordedBy)),
        )
}
