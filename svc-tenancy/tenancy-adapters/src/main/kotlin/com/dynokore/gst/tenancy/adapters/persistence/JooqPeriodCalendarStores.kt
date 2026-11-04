package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoding
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.CALENDAR_PACK_VERSION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PERIOD_EXTENSION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PERIOD_FILING_FACT
import com.dynokore.gst.tenancy.application.period.ExtensionRecord
import com.dynokore.gst.tenancy.application.period.ExtensionRequest
import com.dynokore.gst.tenancy.application.period.ExtensionScope
import com.dynokore.gst.tenancy.application.period.ExtensionScopeKind
import com.dynokore.gst.tenancy.application.period.ExtensionTarget
import com.dynokore.gst.tenancy.application.period.FilingFact
import com.dynokore.gst.tenancy.application.period.FilingFactStore
import com.dynokore.gst.tenancy.application.period.PeriodExtensionStore
import com.dynokore.gst.tenancy.application.period.StagedCalendar
import com.dynokore.gst.tenancy.application.period.StagedCalendarPacks
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The recorded extensions of the bound tenant's period records (jOOQ only, CS-18). */
class JooqPeriodExtensionStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : PeriodExtensionStore {
    override fun forPeriod(
        entityId: UUID,
        periodRef: String,
    ): List<ExtensionRecord> =
        dsl
            .selectFrom(PERIOD_EXTENSION)
            .where(PERIOD_EXTENSION.ENTITY_ID.eq(entityId).and(PERIOD_EXTENSION.PERIOD_REF.eq(periodRef)))
            .orderBy(PERIOD_EXTENSION.RECORDED_AT)
            .fetch()
            .map {
                val target = ExtensionTarget.valueOf(it.target.column("target"))
                val filing = it.newFilingDueDate
                val submission = it.newSubmissionDueDate
                ExtensionRecord(
                    periodRef,
                    ExtensionRequest(
                        target,
                        if (target ==
                            ExtensionTarget.SUBMISSION_DUE_DATE
                        ) {
                            checkNotNull(submission)
                        } else {
                            checkNotNull(filing)
                        },
                        if (target == ExtensionTarget.BOTH) submission else null,
                        ExtensionScope(
                            ExtensionScopeKind.valueOf(it.scopeKind.column("scope_kind")),
                            refsOf(it.documentRefs.column("document_refs")),
                            it.scopeDateFrom,
                            it.scopeDateTo,
                        ),
                        it.irasReference.column("iras_reference"),
                        it.evidenceRef.column("evidence_ref"),
                    ),
                    it.originalFilingDueDate,
                    it.recordedBy.column("recorded_by"),
                    it.recordedAt.column("recorded_at").toInstant(),
                )
            }

    override fun extendedPeriods(entityId: UUID): Set<String> =
        dsl
            .selectDistinct(PERIOD_EXTENSION.PERIOD_REF)
            .from(PERIOD_EXTENSION)
            .where(PERIOD_EXTENSION.ENTITY_ID.eq(entityId))
            .fetch(PERIOD_EXTENSION.PERIOD_REF)
            .map { it.column("period_ref") }
            .toSet()

    override fun insert(
        entityId: UUID,
        record: ExtensionRecord,
    ) {
        val request = record.request
        dsl
            .insertInto(PERIOD_EXTENSION)
            .set(PERIOD_EXTENSION.ID, UUID.randomUUID())
            .set(PERIOD_EXTENSION.TENANT_ID, tenants.current().value)
            .set(PERIOD_EXTENSION.ENTITY_ID, entityId)
            .set(PERIOD_EXTENSION.PERIOD_REF, record.periodRef)
            .set(PERIOD_EXTENSION.TARGET, request.target.name)
            .set(PERIOD_EXTENSION.SCOPE_KIND, request.scope.kind.name)
            .set(PERIOD_EXTENSION.DOCUMENT_REFS, request.scope.documentRefs.joinToString(","))
            .set(PERIOD_EXTENSION.SCOPE_DATE_FROM, request.scope.dateFrom)
            .set(PERIOD_EXTENSION.SCOPE_DATE_TO, request.scope.dateTo)
            .set(PERIOD_EXTENSION.ORIGINAL_FILING_DUE_DATE, record.originalFilingDueDate)
            .set(PERIOD_EXTENSION.NEW_FILING_DUE_DATE, request.newFilingDueDate)
            .set(PERIOD_EXTENSION.NEW_SUBMISSION_DUE_DATE, request.newSubmission)
            .set(PERIOD_EXTENSION.IRAS_REFERENCE, request.irasReference)
            .set(PERIOD_EXTENSION.EVIDENCE_REF, request.evidenceRef)
            .set(PERIOD_EXTENSION.RECORDED_BY, record.recordedBy)
            .set(PERIOD_EXTENSION.RECORDED_AT, record.recordedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    private fun refsOf(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(',')
}

/** The replica of PeriodFilingStatusChanged (jOOQ only, CS-18); the greatest `recordedAt` per period is kept. */
class JooqFilingFactStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : FilingFactStore {
    override fun keep(fact: FilingFact): Boolean =
        dsl
            .insertInto(PERIOD_FILING_FACT)
            .set(PERIOD_FILING_FACT.TENANT_ID, tenants.current().value)
            .set(PERIOD_FILING_FACT.ENTITY_REF, fact.entityRef)
            .set(PERIOD_FILING_FACT.PERIOD_REF, fact.periodRef)
            .set(PERIOD_FILING_FACT.SUBMISSION_LATE, fact.submissionLate)
            .set(PERIOD_FILING_FACT.GOVERNING_FILED_AT, fact.governingFiledAt?.atOffset(ZoneOffset.UTC))
            .set(PERIOD_FILING_FACT.CONFLICT, fact.conflict)
            .set(PERIOD_FILING_FACT.RECORDED_AT, fact.recordedAt.atOffset(ZoneOffset.UTC))
            .onConflict(PERIOD_FILING_FACT.TENANT_ID, PERIOD_FILING_FACT.ENTITY_REF, PERIOD_FILING_FACT.PERIOD_REF)
            .doUpdate()
            .set(PERIOD_FILING_FACT.SUBMISSION_LATE, fact.submissionLate)
            .set(PERIOD_FILING_FACT.GOVERNING_FILED_AT, fact.governingFiledAt?.atOffset(ZoneOffset.UTC))
            .set(PERIOD_FILING_FACT.CONFLICT, fact.conflict)
            .set(PERIOD_FILING_FACT.RECORDED_AT, fact.recordedAt.atOffset(ZoneOffset.UTC))
            .where(PERIOD_FILING_FACT.RECORDED_AT.lt(fact.recordedAt.atOffset(ZoneOffset.UTC)))
            .execute() > 0

    override fun forEntity(entityRef: String): Map<String, FilingFact> =
        dsl
            .selectFrom(PERIOD_FILING_FACT)
            .where(PERIOD_FILING_FACT.ENTITY_REF.eq(entityRef))
            .fetch()
            .associate {
                val period = it.periodRef.column("period_ref")
                period to
                    FilingFact(
                        entityRef,
                        period,
                        it.submissionLate.column("submission_late"),
                        it.governingFiledAt?.toInstant(),
                        it.conflict.column("conflict"),
                        it.recordedAt.column("recorded_at").toInstant(),
                    )
            }
}

/** A staged calendar pack version by identifier and version (the activation is not required), decoded (INV-T-5055). */
class JooqStagedCalendarPacks(
    private val dsl: DSLContext,
    private val decoder: CalendarPackDecoder,
    private val mapper: ObjectMapper,
) : StagedCalendarPacks {
    override fun find(
        packIdentifier: String,
        version: String,
    ): StagedCalendar? {
        val payload =
            dsl
                .select(CALENDAR_PACK_VERSION.PAYLOAD)
                .from(CALENDAR_PACK_VERSION)
                .where(CALENDAR_PACK_VERSION.PACK_IDENTIFIER.eq(packIdentifier))
                .and(CALENDAR_PACK_VERSION.PACK_VERSION.eq(version))
                .fetchOne(CALENDAR_PACK_VERSION.PAYLOAD)
        val pack = payload?.let { (decoder.decode(it) as? CalendarPackDecoding.Decoded)?.pack }
        val from = payload?.let { mapper.readTree(it)["effectiveFrom"]?.takeIf { node -> node.isString }?.asString() }
        return if (pack != null && from != null) StagedCalendar(pack, LocalDate.parse(from)) else null
    }
}
