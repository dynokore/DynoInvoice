package com.dynokore.platform.reconciliationengine

import java.math.BigDecimal

fun d(s: String) = BigDecimal(s)

/** Evidence map for one key. */
fun ev(
    key: String,
    vararg records: Rec,
): Map<String, List<EvidenceRecord>> = mapOf(key to records.toList())

data class Rec(
    override val reference: String,
    override val amount: BigDecimal,
    val kind: String,
) : EvidenceRecord

val EXCLUDED = Category("EXCLUDED_TXN", "Excluded transaction")
val PRE = Category("PRE_OBLIGATION", "Before the obligation start")
val OUTSIDE = Category("OUTSIDE_BY_RULE", "Outside submission by rule")

fun rule(
    category: Category,
    key: String,
    kind: String,
) = ClassificationRule(category, key, { (it as Rec).kind == kind })
