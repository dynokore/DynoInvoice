package com.dynokore.platform.serviceruntime.logging

/**
 * The W3C Trace Context `traceparent` header (STK-34), parsed into its four
 * dash-separated fields: `version-traceId-parentId-traceFlags`. Framework-free
 * (ARC-16): no OpenTelemetry or Micrometer Tracing type appears here, only the
 * wire format every service carries the trace across a call with and supplies to
 * the platform-event envelope (this task's done-when: "W3C trace context
 * supplied to the envelope"). Building that envelope is `outbox-and-queue`'s job,
 * not this module's (that module has no code yet either) — this type is the
 * primitive a future envelope writer reads [traceId] from.
 */
data class TraceParent(
    val version: String,
    val traceId: String,
    val parentId: String,
    val traceFlags: String,
) {
    /** Renders back to the wire format, unchanged from a round-tripped [parse]. */
    override fun toString(): String = "$version-$traceId-$parentId-$traceFlags"

    companion object {
        private val FIELD_PATTERN = Regex("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

        /** Parses a `traceparent` header value, or `null` if it is not well-formed W3C Trace Context. */
        fun parse(header: String): TraceParent? {
            val groups = FIELD_PATTERN.matchEntire(header.trim())?.groupValues ?: return null
            val traceId = groups[TRACE_ID_GROUP]
            val parentId = groups[PARENT_ID_GROUP]
            val allZero = traceId.all { it == '0' } || parentId.all { it == '0' }
            val parent = TraceParent(groups[VERSION_GROUP], traceId, parentId, groups[TRACE_FLAGS_GROUP])
            return if (allZero) null else parent
        }

        // groupValues[0] is the whole match; the four capture groups follow in wire-format order.
        private const val VERSION_GROUP = 1
        private const val TRACE_ID_GROUP = 2
        private const val PARENT_ID_GROUP = 3
        private const val TRACE_FLAGS_GROUP = 4
    }
}
