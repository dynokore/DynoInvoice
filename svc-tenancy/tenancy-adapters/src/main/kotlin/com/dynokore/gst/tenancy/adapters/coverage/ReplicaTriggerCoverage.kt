package com.dynokore.gst.tenancy.adapters.coverage

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TRIGGER_COVERAGE_REPLICA
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.domain.GoLiveGate
import org.jooq.DSLContext

/**
 * The [TriggerCoverage] port of goLive bound to the replica of `ingestion.trigger_coverage` (INV-REQ-788,
 * INV-AC-20943). It reads the rows of the entity on the tenant-bound [DSLContext], so forced row-level security hides
 * every other tenant's rows. It fails closed (OWASP ASVS V1): no row for the entity, or any exception from the read,
 * is [UnavailableInput] with [GoLiveGate.COVERAGE_UNKNOWN], never an empty (passing) answer. Otherwise it returns the
 * classes without a row, in declaration order; any stored status (mapped, waived, covered by another solution) covers
 * a class.
 */
class ReplicaTriggerCoverage(
    private val dsl: DSLContext,
) : TriggerCoverage {
    @Suppress("TooGenericExceptionCaught") // any read failure fails closed
    override fun uncovered(entityRef: String): List<String> {
        val t = TRIGGER_COVERAGE_REPLICA
        val covered =
            try {
                dsl
                    .select(t.TRIGGER_CLASS)
                    .from(t)
                    .where(t.ENTITY_REF.eq(entityRef))
                    .fetch(t.TRIGGER_CLASS)
                    .toSet()
            } catch (e: RuntimeException) {
                throw UnavailableInput(GoLiveGate.COVERAGE_UNKNOWN).apply { initCause(e) }
            }
        if (covered.isEmpty()) throw UnavailableInput(GoLiveGate.COVERAGE_UNKNOWN)
        return TRIGGER_CLASSES.filter { it !in covered }
    }

    companion object {
        /** The five classes of `ingestion/openapi.yaml` `TriggerClass`, in declaration order. */
        val TRIGGER_CLASSES =
            listOf(
                "OUTBOUND_DOCUMENT_ISSUED",
                "INBOUND_DOCUMENT_RECORDED",
                "AGGREGABLE_BATCH_CLOSED",
                "JOURNAL_ONLY_SUPPLY",
                "THIRD_PARTY_ISSUANCE",
            )
    }
}
