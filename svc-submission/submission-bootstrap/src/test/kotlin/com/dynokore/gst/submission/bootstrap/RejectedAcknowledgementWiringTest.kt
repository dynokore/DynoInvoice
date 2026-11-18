package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.AcknowledgementOutcomeRouter
import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedHandler
import com.dynokore.gst.submission.application.queue.RejectedAcknowledgementHandler
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.serviceruntime.tracing.TracedEventHandler
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import java.nio.file.Path

/** INV-AC-24283: the consumer registered for AcknowledgementReceived routes by outcome (offline TPL-18 context). */
class RejectedAcknowledgementWiringTest {
    private fun field(
        target: Any,
        name: String,
    ): Any =
        target.javaClass.declaredFields
            .first { it.name == name }
            .also { it.isAccessible = true }
            .get(target)

    /** The first [EventHandler] reachable from [root] through its fields (the consumer keeps it inside its ledger). */
    private fun handlerOf(
        root: Any,
        depth: Int = 0,
    ): EventHandler? {
        val reachable = root.javaClass.name.startsWith("com.dynokore.platform.outboxandqueue") && depth <= 4
        return when {
            root is EventHandler -> {
                root
            }

            !reachable -> {
                null
            }

            else -> {
                root.javaClass.declaredFields
                    .filter {
                        !it.type.isPrimitive &&
                            !java.lang.reflect.Modifier
                                .isStatic(it.modifiers)
                    }.firstNotNullOfOrNull { f ->
                        f.isAccessible = true
                        f.get(root)?.let { handlerOf(it, depth + 1) }
                    }
            }
        }
    }

    private fun unwrapped(handler: Any): Any = if (handler is TracedEventHandler) handler.delegate else handler

    @Test
    fun `INV-AC-24283 the one consumer of the event holds the router with the two handlers inside`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            val consumers =
                context
                    .getBeansOfType(
                        JdbcInboxConsumer::class.java,
                    ).filterKeys { it.startsWith("acknowledgement") }
            consumers.keys shouldBe setOf("acknowledgementReceivedConsumer")
            val handler = unwrapped(checkNotNull(handlerOf(consumers.getValue("acknowledgementReceivedConsumer"))))
            handler.shouldBeInstanceOf<AcknowledgementOutcomeRouter>()
            unwrapped(field(handler, "accepted")).shouldBeInstanceOf<AcknowledgementReceivedHandler>()
            unwrapped(field(handler, "rejected")).shouldBeInstanceOf<RejectedAcknowledgementHandler>()
            (field(handler, "accepted") as EventHandler) shouldBe context.getBean("acknowledgementReceivedHandler")
            (field(handler, "rejected") as EventHandler) shouldBe context.getBean("rejectedAcknowledgementHandler")
        }
    }

    @Test
    fun `INV-AC-24283 a context without the ErrorCatalogue bean fails to start`(
        @TempDir dir: Path,
    ) {
        var failure: Throwable? = null
        SubmissionContext
            .offline(dir)
            .withInitializer { context ->
                // the bean of OutcomeConfiguration is removed: the rejection handler has nothing to resolve
                context.addBeanFactoryPostProcessor {
                    (it as BeanDefinitionRegistry).removeBeanDefinition("errorCatalogue")
                }
            }.run { failure = it.startupFailure }
        (failure != null) shouldBe true
    }
}
