package com.dynokore.platform.evidencestore

import java.time.LocalDate

/**
 * FR-D.4's deletion precondition, as a pure decision — no port, nothing to
 * wire or mock: "nothing is removed before the end of retention" (INV-REQ-635)
 * and a hold always wins regardless of elapsed time. The actual deletion
 * operation (partition detach-and-drop, co-approved by two operator people) is
 * an evidence-svc orchestration concern, out of this kernel module's scope;
 * this is only the yes/no this module can answer on its own.
 */
object RetentionGate {
    fun canDelete(
        retention: RetentionPeriod,
        periodEnd: LocalDate,
        asOf: LocalDate,
        held: Boolean,
    ): Boolean = !held && !asOf.isBefore(periodEnd.plusYears(retention.years.toLong()))
}
