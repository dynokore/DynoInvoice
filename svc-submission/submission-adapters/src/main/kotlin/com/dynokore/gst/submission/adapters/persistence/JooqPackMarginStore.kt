package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.CalendarPackActivation
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.CalendarPackVersion
import com.dynokore.gst.submission.application.pack.CalendarActivation
import com.dynokore.gst.submission.application.pack.CalendarActivationReader
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.pack.PackMarginStore
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.LocalDate

/**
 * [PackMarginStore] and [CalendarActivationReader] over `submission.calendar_pack_version` and
 * `submission.calendar_pack_activation`. Both writes are `insert ... on conflict do nothing` (the first row of an
 * identifier and version wins) and return whether a row was inserted. Forced row level security and the explicit
 * tenant predicate both apply; the runtime role has no update or delete grant.
 */
class JooqPackMarginStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : PackMarginStore,
    CalendarActivationReader {
    private val v = CalendarPackVersion.CALENDAR_PACK_VERSION
    private val a = CalendarPackActivation.CALENDAR_PACK_ACTIVATION

    override fun stageCalendar(
        tenant: TenantId,
        packIdentifier: String,
        version: String,
        margin: PackMargin?,
    ): Boolean =
        runner.inTransaction {
            dsl
                .insertInto(v)
                .set(v.TENANT_ID, tenant.value)
                .set(v.PACK_IDENTIFIER, packIdentifier)
                .set(v.PACK_VERSION, version)
                .set(v.MARGIN_VALUE, margin?.value)
                .set(v.MARGIN_UNIT, margin?.unit?.name)
                .onConflict(v.TENANT_ID, v.PACK_IDENTIFIER, v.PACK_VERSION)
                .doNothing()
                .execute() == 1
        }

    override fun recordActivation(
        tenant: TenantId,
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
    ): Boolean =
        runner.inTransaction {
            dsl
                .insertInto(a)
                .set(a.TENANT_ID, tenant.value)
                .set(a.PACK_IDENTIFIER, packIdentifier)
                .set(a.PACK_VERSION, version)
                .set(a.EFFECTIVE_FROM, effectiveFrom)
                .onConflict(a.TENANT_ID, a.PACK_IDENTIFIER, a.PACK_VERSION)
                .doNothing()
                .execute() == 1
        }

    override fun calendarActivations(
        tenant: TenantId,
        onOrBefore: LocalDate,
    ): List<CalendarActivation> =
        runner.inTransaction {
            dsl
                .select(a.PACK_IDENTIFIER, a.PACK_VERSION, a.EFFECTIVE_FROM, v.MARGIN_VALUE, v.MARGIN_UNIT)
                .from(a)
                .join(v)
                .on(
                    v.TENANT_ID
                        .eq(a.TENANT_ID)
                        .and(v.PACK_IDENTIFIER.eq(a.PACK_IDENTIFIER))
                        .and(v.PACK_VERSION.eq(a.PACK_VERSION)),
                ).where(a.TENANT_ID.eq(tenant.value))
                .and(a.EFFECTIVE_FROM.le(onOrBefore))
                .fetch()
                .map { r ->
                    CalendarActivation(
                        checkNotNull(r.get(a.PACK_IDENTIFIER)),
                        checkNotNull(r.get(a.PACK_VERSION)),
                        checkNotNull(r.get(a.EFFECTIVE_FROM)),
                        PackMargin.of(r.get(v.MARGIN_VALUE)?.toLong(), r.get(v.MARGIN_UNIT)),
                    )
                }
        }
}
