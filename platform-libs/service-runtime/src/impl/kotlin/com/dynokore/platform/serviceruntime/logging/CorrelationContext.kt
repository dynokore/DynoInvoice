package com.dynokore.platform.serviceruntime.logging

/**
 * Binds [CorrelationId] (and, within a narrower scope, [CausationId]) into MDC
 * for the duration of a unit of work (§11.6, INV-REQ-2578): carried from the
 * ingestion request to the acknowledgement across every service, and available
 * to whatever supplies the platform-event envelope's own trace fields
 * (`outbox-and-queue`, no code yet) — this is the primitive that future writer
 * reads from, not the envelope itself.
 */
object CorrelationContext {
    /** Binds [correlationId] for [block]'s duration (typically the whole request). */
    fun <T> withCorrelation(
        correlationId: CorrelationId,
        block: () -> T,
    ): T = MdcContext.withValue(MdcKey.CORRELATION_ID, correlationId.value, block)

    /** Binds [causationId] for [block]'s duration (typically one derived-event handler). */
    fun <T> withCausation(
        causationId: CausationId,
        block: () -> T,
    ): T = MdcContext.withValue(MdcKey.CAUSATION_ID, causationId.value, block)

    /** The correlation id bound in the current thread's MDC, if any. */
    fun currentCorrelationId(): CorrelationId? = org.slf4j.MDC.get(MdcKey.CORRELATION_ID.key)?.let(::CorrelationId)

    /** The causation id bound in the current thread's MDC, if any. */
    fun currentCausationId(): CausationId? = org.slf4j.MDC.get(MdcKey.CAUSATION_ID.key)?.let(::CausationId)
}
