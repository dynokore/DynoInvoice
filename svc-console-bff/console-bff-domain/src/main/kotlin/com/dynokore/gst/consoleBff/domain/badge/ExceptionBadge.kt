package com.dynokore.gst.consoleBff.domain.badge

import java.time.LocalDate

enum class CodeSeverity { BLOCK, HOLD, WARN, INFO }

enum class CodeDisposition { AUTO_RELEASE, AUTO_RETRY, MANUAL, OVERRIDABLE, TERMINAL }

enum class HoldRelease { DATA, ELECTION }

data class CodeFacts(
    val severity: CodeSeverity,
    val disposition: CodeDisposition?,
    val holdRelease: HoldRelease?,
)

enum class ItemState { OPEN, RESOLVED, OVERRIDDEN }

data class BadgeItem(
    val entityRef: String,
    val code: String,
    val remediationOwner: String,
    val state: ItemState,
    val dueDate: LocalDate?,
)

data class BadgeResult(
    val count: Int,
    val urgent: Boolean,
)

/**
 * Exceptions badge (INV-REQ-970, INV-REQ-1016): counts groups (code, remediationOwner) that need action,
 * never items. A code absent from [facts] is counted (fail closed). Urgent when any OPEN item is due
 * on or before today plus [urgentWithinDays] (overdue included), whether or not its group is counted.
 */
fun exceptionBadge(
    items: List<BadgeItem>,
    facts: Map<String, CodeFacts>,
    today: LocalDate,
    urgentWithinDays: Int,
): BadgeResult {
    require(urgentWithinDays >= 1) { "urgentWithinDays must be at least 1" }
    val open = items.filter { it.state == ItemState.OPEN }
    val count =
        open
            .map { it.code to it.remediationOwner }
            .distinct()
            .count { (code, _) -> needsAction(facts[code]) }
    val limit = today.plusDays(urgentWithinDays.toLong())
    val urgent = open.any { item -> item.dueDate?.let { !it.isAfter(limit) } ?: false }
    return BadgeResult(count, urgent)
}

private fun needsAction(f: CodeFacts?): Boolean =
    when {
        f == null -> {
            true
        }

        f.severity == CodeSeverity.BLOCK -> {
            true
        }

        f.severity == CodeSeverity.WARN -> {
            f.disposition == CodeDisposition.MANUAL
        }

        f.severity == CodeSeverity.HOLD -> {
            when (f.disposition) {
                CodeDisposition.MANUAL -> true
                CodeDisposition.AUTO_RELEASE -> f.holdRelease != HoldRelease.DATA
                else -> false
            }
        }

        else -> {
            false
        }
    }
