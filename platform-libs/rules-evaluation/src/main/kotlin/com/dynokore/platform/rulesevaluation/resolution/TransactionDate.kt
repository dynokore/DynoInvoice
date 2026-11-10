package com.dynokore.platform.rulesevaluation.resolution

import java.time.LocalDate

/**
 * The date a pack lookup resolves against: the document's own transaction date, never the current date
 * (INV-REQ-770, INV-BR-215). A lookup takes this type and nothing else, so the clock cannot reach the resolution path.
 */
@JvmInline
value class TransactionDate(
    val value: LocalDate,
) {
    override fun toString(): String = value.toString()
}
