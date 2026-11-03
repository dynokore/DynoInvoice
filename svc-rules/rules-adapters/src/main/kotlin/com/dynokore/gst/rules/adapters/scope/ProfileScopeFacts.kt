package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The two dates of the entity profile the submission window reads (INV-Q-16175). */
data class ProfileDates(
    val obligationStartDate: TransactionDate?,
    val registrationEndDate: TransactionDate?,
)

/**
 * Reads `obligationStartDate` and `registrationEndDate` of an entity from the structure replica of service-runtime
 * (INV-REQ-376, INV-REQ-377, INV-REQ-378), through `StructureNode.attribute(name, transactionDate)`: the entry in force
 * on the transaction date, never a future-dated one. No other attribute is read. A missing node, a missing entry or a
 * value that is not an ISO date gives null, which the scope rules treat as not yet in force (fail closed).
 */
class ProfileScopeFacts(
    private val structure: StructureReplica,
    private val clock: Clock,
) {
    fun datesOf(
        tenant: TenantId,
        entityRef: String,
        date: TransactionDate,
    ): ProfileDates {
        val node = nodeOf(tenant, entityRef) ?: return ProfileDates(null, null)
        return ProfileDates(
            dateOf(node, OBLIGATION_START_DATE, date),
            dateOf(node, REGISTRATION_END_DATE, date),
        )
    }

    private fun nodeOf(
        tenant: TenantId,
        entityRef: String,
    ): StructureNode? =
        when (val found = structure.node(tenant, entityRef, Deadline.after(BUDGET, clock))) {
            is Lookup.Local -> found.value
            is Lookup.Filled -> found.value
            is Lookup.LastKnown -> found.value
            Lookup.Absent, Lookup.Unavailable -> null
        }

    private fun dateOf(
        node: StructureNode,
        attribute: String,
        date: TransactionDate,
    ): TransactionDate? =
        node.attribute(attribute, date.value)?.let {
            try {
                TransactionDate(LocalDate.parse(it))
            } catch (_: DateTimeParseException) {
                null
            }
        }

    companion object {
        const val OBLIGATION_START_DATE = "obligationStartDate"
        const val REGISTRATION_END_DATE = "registrationEndDate"
        private val BUDGET: Duration = Duration.ofSeconds(1)
    }
}
