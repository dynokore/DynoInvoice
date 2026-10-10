package com.dynokore.platform.outboxandqueue

/** A received record is not a valid envelope (ARC 4.2 step 5); it goes to dead-letter triage, not to the handler. */
class ContractViolation(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
