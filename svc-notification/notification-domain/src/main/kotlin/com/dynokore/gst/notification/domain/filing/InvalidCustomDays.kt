package com.dynokore.gst.notification.domain.filing

/** Typed value of VP-16 CUSTOM_DAYS: days before the due date, `MIN_DAY..MAX_DAY`, distinct, 1..`MAX_ITEMS` items. */
const val MIN_DAY = 1
const val MAX_DAY = 90
const val MAX_ITEMS = 5

enum class InvalidReason {
    EMPTY,
    TOO_MANY,
    DUPLICATE,
    OUT_OF_RANGE,
}

/** A custom day list outside the VP-16 typed value; no schedule is returned. */
class InvalidCustomDays(
    val reason: InvalidReason,
) : RuntimeException("invalid custom days: $reason")
