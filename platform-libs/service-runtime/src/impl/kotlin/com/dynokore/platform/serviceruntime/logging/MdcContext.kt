package com.dynokore.platform.serviceruntime.logging

import org.slf4j.MDC

/**
 * The only sanctioned way application code writes Mapped Diagnostic Context
 * (STK-35, CS-34): every key is a closed [MdcKey], never a raw string, so a
 * value that is not on the allow-list can never be written through this API.
 * [MdcAllowListTurboFilter] is the defence-in-depth backstop for MDC entries set
 * some other way (a raw `org.slf4j.MDC.put` call, or a third-party library).
 */
object MdcContext {
    fun put(
        key: MdcKey,
        value: String,
    ) {
        MDC.put(key.key, value)
    }

    fun remove(key: MdcKey) {
        MDC.remove(key.key)
    }

    /** Sets [key] to [value] for [block]'s duration, restoring whatever [key] held before (or clearing it). */
    fun <T> withValue(
        key: MdcKey,
        value: String,
        block: () -> T,
    ): T {
        val previous = MDC.get(key.key)
        put(key, value)
        try {
            return block()
        } finally {
            if (previous == null) remove(key) else MDC.put(key.key, previous)
        }
    }
}
