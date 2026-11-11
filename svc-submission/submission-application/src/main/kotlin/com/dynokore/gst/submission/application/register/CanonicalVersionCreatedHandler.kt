package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.deadline.SubmissionDeadline
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.ListFactsResult
import com.dynokore.gst.submission.domain.scope.ScopeRoute
import com.dynokore.gst.submission.domain.scope.ScopeVerdictRouting
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Writes the register entry from `CanonicalVersionCreated` (INV-REQ-975, INV-Q-16315 A). It runs in the consumer's
 * transaction like `PayloadReadyHandler`. Incomplete list facts, an unrecognised verdict and an unknown kind are a
 * [ContractViolation] (final, dead letter at once) and write nothing; an unknown period is an
 * [IllegalStateException] (rolled back and retried). Messages carry no field value.
 */
class CanonicalVersionCreatedHandler(
    private val tenants: TenantContext,
    private val reader: CanonicalVersionCreatedDataReader,
    private val deadlines: FilingDeadlineSource,
    private val kinds: DocumentKindResolver,
    private val store: RegisterEntryStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val where = "CanonicalVersionCreated ${data.docUuid} version ${data.version}"
        val facts = factsOf(data, where)
        val inScope = inScopeOf(data.scopeVerdict, where)
        val kind = kinds.kindOf(data) ?: throw ContractViolation("$where: no register kind for the document")
        val tenant = tenants.current()
        val entityRef = event.entity.value
        val inputs =
            deadlines.inputs(tenant, entityRef, data.prescribedPeriodRef)
                ?: error("$where: filing deadline of the period is not known")
        val entry =
            RegisterEntry(
                docUuid = data.docUuid,
                entityRef = entityRef,
                periodRef = data.prescribedPeriodRef,
                dueDate = SubmissionDeadline.of(inputs.filingDueDate, inputs.filedOn).date,
                inScope = inScope,
                kind = kind,
                facts = facts,
                sourceVersion = data.version,
            )
        return when (store.upsert(tenant, entry)) {
            UpsertResult.INSERTED, UpsertResult.UPDATED -> HandleOutcome.APPLIED
            UpsertResult.UNCHANGED -> HandleOutcome.IGNORED
        }
    }

    private fun factsOf(
        data: CanonicalVersionCreatedData,
        where: String,
    ): ListFacts =
        when (val result = ListFactsPolicy.extract(data.payload)) {
            is ListFactsResult.Complete -> {
                result.facts
            }

            is ListFactsResult.Incomplete -> {
                throw ContractViolation("$where: incomplete list facts: ${result.missing.sorted().joinToString()}")
            }
        }

    private fun inScopeOf(
        verdict: String,
        where: String,
    ): Boolean =
        when (ScopeVerdictRouting.decide(verdict)) {
            ScopeRoute.Submittable -> true
            is ScopeRoute.NotSubmittable -> false
            is ScopeRoute.Unrecognised -> throw ContractViolation("$where: unrecognised scope verdict")
        }
}
