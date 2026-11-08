package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.outboxandqueue.EventBinding
import com.dynokore.platform.outboxandqueue.EventHandler
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * Decorates every [EventBinding] bean (PRODUCER span) and [EventHandler] bean (CONSUMER span), including those a
 * service defines. Registered only when the telemetry endpoint is set, so otherwise the beans are untouched. A bean
 * already decorated is not decorated twice. The tracer is resolved lazily so this post-processor does not force the
 * tracer provider early.
 */
class MessagingSpanPostProcessor(
    private val provider: ObjectProvider<SdkTracerProvider>,
) : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any =
        when {
            bean is TracedEventBinding || bean is TracedEventHandler -> bean
            bean is EventBinding -> TracedEventBinding(bean, tracer())
            bean is EventHandler -> TracedEventHandler(bean, tracer())
            else -> bean
        }

    private fun tracer() = provider.getObject().get(TRACER_NAME)

    companion object {
        const val TRACER_NAME = "com.dynokore.platform.serviceruntime.tracing"
    }
}
