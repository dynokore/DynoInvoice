package com.dynokore.platform.reconciliationengine

import java.math.BigDecimal

const val MAX_KEY_LENGTH = 64

/** Which side had no entry for a key; that side counts as zero. */
enum class MissingSide { NONE, RETURN, SUBMITTED }

/** One line of the two-sided comparison. `variance = returnAmount - submittedAmount`, scale kept. */
data class Comparison(
    val key: String,
    val returnAmount: BigDecimal,
    val submittedAmount: BigDecimal,
    val variance: BigDecimal,
    val missingSide: MissingSide,
)

/** Two-sided comparison by an opaque key. */
object Comparisons {
    /**
     * One line per key present in either map, ordered by key. A missing key counts as zero on that side. Throws
     * [IllegalArgumentException] for a blank key or a key over [MAX_KEY_LENGTH] characters. The inputs are not
     * modified.
     */
    fun compare(
        returnSide: Map<String, BigDecimal>,
        submittedSide: Map<String, BigDecimal>,
    ): List<Comparison> {
        (returnSide.keys + submittedSide.keys).forEach(::requireValidKey)
        return (returnSide.keys + submittedSide.keys).sorted().map { key ->
            val ret = returnSide[key]
            val sub = submittedSide[key]
            Comparison(
                key = key,
                returnAmount = ret ?: BigDecimal.ZERO,
                submittedAmount = sub ?: BigDecimal.ZERO,
                variance = (ret ?: BigDecimal.ZERO).subtract(sub ?: BigDecimal.ZERO),
                missingSide =
                    when {
                        ret == null -> MissingSide.RETURN
                        sub == null -> MissingSide.SUBMITTED
                        else -> MissingSide.NONE
                    },
            )
        }
    }
}

internal fun requireValidKey(key: String) {
    require(key.isNotBlank()) { "A key must not be blank" }
    require(key.length <= MAX_KEY_LENGTH) { "A key must not exceed $MAX_KEY_LENGTH characters" }
}
