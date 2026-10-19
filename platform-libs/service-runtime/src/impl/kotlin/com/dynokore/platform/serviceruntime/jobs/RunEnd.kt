package com.dynokore.platform.serviceruntime.jobs

/** How a run ended: the failure's class name (null on success) and the duration. */
internal class RunEnd(
    val failure: String?,
    val durationMillis: Long,
)
