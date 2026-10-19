package com.dynokore.gst.tenancy.domain

/**
 * The code rules of INV-REQ-850 item 7, over the codes the tenant already holds (one namespace for entities and
 * business units).
 */
object CodeRules {
    /** An entity's code: the one entered, or its UEN unless another entity of the tenant already uses it. */
    fun entityCode(
        entered: String?,
        uen: String?,
        taken: Set<ShortCode>,
    ): CodeOutcome<ShortCode> {
        val text = entered ?: uen ?: return CodeOutcome.Refused(CodeRefusal.NOT_ENTERED)
        return register(text, taken)
    }

    /** A business unit's code is entered at registration. */
    fun businessUnitCode(
        entered: String?,
        taken: Set<ShortCode>,
    ): CodeOutcome<ShortCode> =
        if (entered == null) CodeOutcome.Refused(CodeRefusal.NOT_ENTERED) else register(entered, taken)

    /** A change of code; refused once the code is used in an aggregate number (the fact comes from aggregation-svc). */
    fun change(
        current: ShortCode,
        entered: String,
        taken: Set<ShortCode>,
        usedInAggregateNumber: Boolean,
    ): CodeOutcome<ShortCode> {
        if (usedInAggregateNumber) return CodeOutcome.Refused(CodeRefusal.USED_IN_AGGREGATE_NUMBER)
        return register(entered, taken - current)
    }

    private fun register(
        text: String,
        taken: Set<ShortCode>,
    ): CodeOutcome<ShortCode> =
        when (val parsed = ShortCode.parse(text)) {
            is CodeOutcome.Refused -> {
                parsed
            }

            is CodeOutcome.Accepted -> {
                if (parsed.value in taken) CodeOutcome.Refused(CodeRefusal.DUPLICATE) else parsed
            }
        }
}
