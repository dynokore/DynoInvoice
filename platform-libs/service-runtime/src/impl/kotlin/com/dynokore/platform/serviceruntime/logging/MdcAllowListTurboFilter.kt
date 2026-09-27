package com.dynokore.platform.serviceruntime.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.MDC
import org.slf4j.Marker

/**
 * The masked-field backstop (CS-34, STK-35): strips any Mapped Diagnostic
 * Context entry whose key is not on the [MdcKey] allow-list before the log
 * event is built, so invoice content set through a raw `MDC.put` call — a bug,
 * or a third-party library's own instrumentation — never reaches an emitted log
 * line even though [MdcContext] is the only *sanctioned* way to set MDC.
 *
 * A Logback `TurboFilter`'s `decide` runs synchronously on the logging call's own
 * thread, before the `ILoggingEvent`'s MDC property map is snapshotted — mutating
 * `MDC` here is what makes the removal visible in the eventual event (proved by
 * `MdcAllowListTurboFilterTest`'s masked-field test).
 */
class MdcAllowListTurboFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level?,
        format: String?,
        params: Array<Any>?,
        t: Throwable?,
    ): FilterReply {
        val current = MDC.getCopyOfContextMap() ?: return FilterReply.NEUTRAL
        current.keys
            .filterNot { it in MdcKey.ALLOWED_NAMES }
            .forEach { MDC.remove(it) }
        return FilterReply.NEUTRAL
    }
}
