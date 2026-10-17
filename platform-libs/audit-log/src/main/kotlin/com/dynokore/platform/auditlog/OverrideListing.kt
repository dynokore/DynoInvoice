package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * Asks for the override register of one [entity] over the transaction dates [from]..[to], both inclusive
 * (INV-AC-1329). The caller supplies the dates of its period; [reader] is the principal whose access is audited.
 *
 * @throws IllegalArgumentException when [to] is before [from] or [reader] is blank.
 */
data class OverrideListQuery(
    val tenant: TenantId,
    val entity: EntityRef,
    val from: LocalDate,
    val to: LocalDate,
    val reader: String,
    val correlation: CorrelationId,
    val causation: CausationId,
) {
    init {
        require(!to.isBefore(from)) { "to ($to) must not be before from ($from)" }
        require(reader.isNotBlank()) { "reader must not be blank" }
    }
}

/** One override of a listing: its [grant] and the [transactionDate] it was given for (null when none was). */
data class OverrideEntry(
    val grant: OverrideGrant,
    val transactionDate: LocalDate?,
)

/**
 * The listing of an entity's overrides. [dated] holds the grants whose transaction date lies in the queried range;
 * [undated] holds every grant of the entity with no transaction date, so that no override is left out of a period
 * listing (fail closed: the owning service sees them and decides). Both are ordered by (transaction date, granted at,
 * id).
 */
data class OverrideListingResult(
    val dated: List<OverrideEntry>,
    val undated: List<OverrideEntry>,
)

/**
 * The override register as a read (INV-REQ-1376, INV-AC-1329). The read is itself audited: the entry is recorded in the
 * same transaction as the select. The library does not decide who may read; the owning service authorises the reader,
 * as it does for an override.
 */
interface OverrideListing {
    /**
     * @throws AuditWriteFailed when the `listOverrides` entry cannot be written; no result is returned.
     */
    fun list(query: OverrideListQuery): OverrideListingResult
}
