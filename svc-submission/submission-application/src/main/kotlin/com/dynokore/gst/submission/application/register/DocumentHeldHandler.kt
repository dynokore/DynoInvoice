package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.application.period.PeriodAnswer
import com.dynokore.gst.submission.application.period.PeriodOfDate
import com.dynokore.gst.submission.domain.register.DocumentDirection
import com.dynokore.gst.submission.domain.register.ProvisionalTransactionDate
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock

/**
 * Places the provisional register entry of a held document (INV-REQ-570 behavior 2). An unknown direction, empty
 * hold reasons and a payload entity that differs from the envelope entity are a [ContractViolation] (dead letter at
 * once); a date outside every stored period is an [IllegalStateException] (rolled back and retried). The event's
 * `prescribedPeriodRef` is never read. Messages carry no field value.
 */
class DocumentHeldHandler(
    private val tenants: TenantContext,
    private val reader: DocumentHeldDataReader,
    private val periods: PeriodOfDate,
    private val elections: DateElectionSource,
    private val store: ProvisionalEntryStore,
    private val clock: Clock,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val where = "DocumentHeld ${data.docUuid}"
        val entityRef = event.entity.value
        val direction = checked(data, entityRef, where)
        val reason = data.holdReasons.first()
        val tenant = tenants.current()
        val date =
            ProvisionalTransactionDate.select(
                direction,
                data.invoiceIssuedOrReceived,
                elections.electionOf(tenant, entityRef),
                data.documentDate,
                data.postingDate,
            )
        val period =
            when (val answer = periods.periodOf(tenant, entityRef, date)) {
                is PeriodAnswer.Known -> answer.periodRef
                PeriodAnswer.Unknown -> error("$where: period of the transaction date is not known")
            }
        val entry =
            ProvisionalEntry(
                docUuid = data.docUuid,
                entityRef = entityRef,
                periodRef = period,
                direction = direction,
                documentDate = data.documentDate,
                transactionDate = date,
                reasonCode = reason,
                docsequence = data.docsequence,
                heldAt = clock.instant(),
            )
        return when (store.place(tenant, entry)) {
            Placement.PLACED -> HandleOutcome.APPLIED
            Placement.UNCHANGED, Placement.ALREADY_REGISTERED -> HandleOutcome.IGNORED
        }
    }

    private fun checked(
        data: DocumentHeldData,
        entityRef: String,
        where: String,
    ): DocumentDirection {
        if (data.holdReasons.isEmpty()) throw ContractViolation("$where: no hold reason")
        if (data.entityRef != entityRef) throw ContractViolation("$where: payload entity differs from the envelope")
        return directionOf(data.direction, where)
    }

    private fun directionOf(
        name: String,
        where: String,
    ): DocumentDirection =
        DocumentDirection.entries.firstOrNull { it.name == name }
            ?: throw ContractViolation("$where: unknown direction")
}
