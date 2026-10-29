package com.dynokore.gst.evidence.domain.retention

import java.time.Duration

data class TierRetrievalTimes(
    val documented: Map<String, Duration>,
    val stated: Map<String, Duration> = emptyMap(),
) {
    init {
        documented.forEach { (tier, time) ->
            require(tier.isNotBlank()) { "documented tier key must not be blank" }
            require(!time.isNegative) { "documented retrieval time of tier '$tier' must not be negative" }
        }
        stated.forEach { (tier, time) ->
            require(tier.isNotBlank()) { "stated tier key must not be blank" }
            require(tier in documented) { "stated tier '$tier' is not a documented tier" }
            require(time.isPositive) { "stated retrieval time of tier '$tier' must be greater than zero" }
        }
    }
}

sealed interface RetrievalNotice {
    data object WithinInteractiveLimit : RetrievalNotice

    data class ShowExpectedTime(
        val expected: Duration,
    ) : RetrievalNotice
}

object ExpectedRetrievalTime {
    fun notice(
        tier: String,
        times: TierRetrievalTimes,
        interactiveLimit: Duration,
    ): RetrievalNotice {
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(!interactiveLimit.isNegative) { "interactive limit must not be negative" }
        val documented = requireNotNull(times.documented[tier]) { "tier '$tier' is not declared" }
        val time = times.stated[tier] ?: documented
        return if (time > interactiveLimit) {
            RetrievalNotice.ShowExpectedTime(time)
        } else {
            RetrievalNotice.WithinInteractiveLimit
        }
    }
}
