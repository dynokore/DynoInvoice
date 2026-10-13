package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.StalenessRule

/**
 * The service's resolved staleness rules: the configured ones ([StalenessProperties.rules]) and any [StalenessRule]
 * beans the service declares, each checked against its provisional ceiling (INV-Q-2991, INV-Q-2987).
 */
class StalenessRules(
    val rules: List<StalenessRule>,
) {
    companion object {
        /**
         * Resolves [configured] (a missing threshold takes [ProvisionalStalenessThresholds.of]) and [declared]. An
         * installation may only tighten (INV-REQ-1165 b4): a threshold longer than its provisional value stops
         * start-up with a message naming the rule; it is never silently clamped.
         */
        fun resolve(
            configured: List<StalenessProperties.Rule>,
            declared: List<StalenessRule> = emptyList(),
        ): StalenessRules {
            val fromProperties =
                configured.mapIndexed { i, rule ->
                    val name = "${StalenessProperties.PREFIX}.rules[$i]"
                    check(rule.consumer.isNotBlank()) { "$name.consumer must name the consumer" }
                    check(rule.eventType.isNotBlank()) { "$name.event-type must name the event type" }
                    StalenessRule(
                        rule.consumer,
                        rule.eventType,
                        rule.threshold ?: ProvisionalStalenessThresholds.of(rule.consumer, rule.eventType),
                    )
                }
            val all = fromProperties + declared
            all.forEach(::requireTightenOnly)
            val keys = all.map { it.consumer to it.eventType }
            check(keys.toSet().size == keys.size) {
                "one staleness rule per consumer and event type: ${keys.groupBy {
                    it
                }.filterValues { it.size > 1 }.keys}"
            }
            return StalenessRules(all)
        }

        private fun requireTightenOnly(rule: StalenessRule) {
            val ceiling = ProvisionalStalenessThresholds.of(rule.consumer, rule.eventType)
            check(rule.threshold <= ceiling) {
                "staleness threshold ${rule.threshold} for consumer ${rule.consumer} on ${rule.eventType} is longer " +
                    "than " +
                    "its provisional $ceiling (INV-Q-2991); an installation may only tighten it (INV-REQ-1165, INV-Q-2987)"
            }
        }
    }
}
