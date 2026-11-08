package com.dynokore.platform.serviceruntime.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context

/** The parent context of a new span: the inbound W3C `traceparent` of the current request, else the root. */
internal object TraceParents {
    fun parentContext(): Context {
        val parent = CurrentTraceParent.current() ?: return Context.root()
        val remote =
            SpanContext.createFromRemoteParent(
                parent.traceId,
                parent.parentId,
                TraceFlags.fromHex(parent.traceFlags, 0),
                TraceState.getDefault(),
            )
        return Context.root().with(Span.wrap(remote))
    }
}
