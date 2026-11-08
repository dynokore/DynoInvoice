package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventBinding
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * Runs [block] inside one messaging span. Attributes are exactly `messaging.system`, `messaging.operation.type`,
 * `messaging.destination.name` (the envelope `type`, unchanged) and `messaging.message.id` (the envelope `id`); no
 * other envelope field is read. A throwable ends the span with ERROR status (no description, no exception event, so
 * no message text is exported) and is rethrown unchanged.
 */
internal object MessagingSpans {
    const val SYSTEM = "gst-event-log"
    const val PUBLISH = "publish"
    const val PROCESS = "process"
    private val SYSTEM_KEY = AttributeKey.stringKey("messaging.system")
    private val OPERATION_KEY = AttributeKey.stringKey("messaging.operation.type")
    private val DESTINATION_KEY = AttributeKey.stringKey("messaging.destination.name")
    private val MESSAGE_ID_KEY = AttributeKey.stringKey("messaging.message.id")

    fun <T> run(
        tracer: Tracer,
        kind: SpanKind,
        operation: String,
        envelope: Envelope,
        block: () -> T,
    ): T {
        val span: Span =
            tracer
                .spanBuilder("${envelope.type} $operation")
                .setSpanKind(kind)
                .setParent(TraceParents.parentContext())
                .setAttribute(SYSTEM_KEY, SYSTEM)
                .setAttribute(OPERATION_KEY, operation)
                .setAttribute(DESTINATION_KEY, envelope.type)
                .setAttribute(MESSAGE_ID_KEY, envelope.id.value)
                .startSpan()
        try {
            return block()
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            span.setStatus(StatusCode.ERROR)
            throw t
        } finally {
            span.end()
        }
    }
}

/** PRODUCER span around [EventBinding.publish]; the contract is otherwise untouched. */
class TracedEventBinding(
    val delegate: EventBinding,
    private val tracer: Tracer,
) : EventBinding {
    override fun publish(
        topic: String,
        envelope: Envelope,
    ) = MessagingSpans.run(tracer, SpanKind.PRODUCER, MessagingSpans.PUBLISH, envelope) {
        delegate.publish(topic, envelope)
    }
}

/** CONSUMER span around [EventHandler.handle]; the contract is otherwise untouched. */
class TracedEventHandler(
    val delegate: EventHandler,
    private val tracer: Tracer,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome =
        MessagingSpans.run(tracer, SpanKind.CONSUMER, MessagingSpans.PROCESS, event) { delegate.handle(event) }
}
