package com.dynokore.gst.aggregation.domain

/** What the type check decided: the category to aggregate under, or the terminal refusal. */
sealed interface IntakeVerdict {
    data class Accepted(
        val type: AggregateType,
    ) : IntakeVerdict

    data class Refused(
        val refusal: NotAggregatable,
    ) : IntakeVerdict
}

/**
 * The type check of the aggregate intake (INV-REQ-410 behaviours 1 to 3): only POS, STI and PCP are aggregated, and
 * anything else is refused with ING-006 and no override, whether it arrives as the `aggregateType` of the intake call,
 * as the category of a transaction-level feed item, or inside the underlying detail of an aggregate.
 */
object AggregateIntakeCheck {
    /** Behaviours 1 and 2: the `aggregateType` of an intake call, or the category of one feed item. */
    fun check(offered: String?): IntakeVerdict =
        AggregateType.parse(offered)?.let { IntakeVerdict.Accepted(it) }
            ?: IntakeVerdict.Refused(NotAggregatable(offered))

    /**
     * Behaviour 3: an aggregate is checked against its underlying detail. One transaction outside the three
     * categories blocks the whole aggregate (nothing is transmitted); the first offender is named.
     */
    fun checkDetail(detailCategories: List<String?>): IntakeVerdict? =
        detailCategories
            .map(::check)
            .filterIsInstance<IntakeVerdict.Refused>()
            .firstOrNull()
}
