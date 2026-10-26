package com.dynokore.gst.notification.domain.filing

import java.time.LocalDate

/** Why a prompt is planned. */
enum class PromptKind {
    PERIOD_READY,
    DAYS_BEFORE_DUE,
}

/** One planned prompt: the Singapore [date], its [kind], and [days] before the due date (null for PERIOD_READY). */
data class PromptOn(
    val date: LocalDate,
    val kind: PromptKind,
    val days: Int?,
)
