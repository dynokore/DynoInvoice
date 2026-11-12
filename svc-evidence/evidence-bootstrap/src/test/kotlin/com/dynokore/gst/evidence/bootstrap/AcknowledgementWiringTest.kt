package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.events.SubmissionAcknowledgedHandler
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock

/**
 * INV-AC-20572: the bootstrap configuration consumes SubmissionAcknowledged with one subscription and one inbox
 * consumer; the database-backed beans are test doubles, the configuration methods are called directly.
 */
class AcknowledgementWiringTest {
    private val type = "com.dynokore.gst.submission.SubmissionAcknowledged.v1"
    private val topic = "gst.submission.submission-acknowledged.v1"
    private val configuration = AcknowledgementConsumerConfiguration()
    private val clock = Clock.systemUTC()
    private val dsl = DSL.using(SQLDialect.POSTGRES)

    private val catalogue = EventConfiguration().eventCatalogue()

    // a test double of an interface: every call fails, so no stub is ever used silently
    private fun <T> stub(
        type: Class<T>,
        vararg more: Class<*>,
    ): T {
        val all = arrayOf(type, *more)
        val proxy =
            Proxy.newProxyInstance(type.classLoader, all) { _, method, _ ->
                throw UnsupportedOperationException("test double: ${method.name}")
            }
        return type.cast(proxy)
    }

    private fun scope(): TenantScope {
        val runner = stub(TransactionRunner::class.java, CurrentConnection::class.java)
        return TenantScope(stub(TenantContext::class.java), runner, runner as CurrentConnection, "evidence-svc")
    }

    private fun consumer(catalogue: EventCatalogue): JdbcInboxConsumer =
        configuration.submissionAcknowledgedConsumer(
            catalogue,
            stub(EventLogReader::class.java),
            scope(),
            RebuildRequiredSink { },
            clock,
            dsl,
            configuration.submissionAcknowledgedHandler(dsl, clock),
        )

    @Test
    fun `INV-AC-20572 the catalogue lists SubmissionAcknowledged on its topic from submission-svc`() {
        val entry = catalogue.find(type)
        assertThat(entry).isNotNull
        assertThat(entry!!.eventType).isEqualTo(type)
        assertThat(entry.topic).isEqualTo(topic)
        assertThat(entry.producers).isEqualTo(setOf("submission-svc"))
        assertThat(entry.recordTopic).isFalse()
        assertThat(catalogue.topic(topic)).isSameAs(entry)
    }

    @Test
    fun `INV-AC-20572 one subscription and one inbox consumer exist for the topic`() {
        assertThat(AcknowledgementConsumerConfiguration.SUBSCRIPTION.topic).isEqualTo(topic)
        assertThat(AcknowledgementConsumerConfiguration.SUBSCRIPTION.consumer)
            .isEqualTo(LocatorConsumerConfiguration.CONSUMER)
        assertThat(AcknowledgementConsumerConfiguration.SUBSCRIPTION.schema).isEqualTo("evidence")
        val consumers =
            AcknowledgementConsumerConfiguration::class.java.declaredMethods.filter {
                JdbcInboxConsumer::class.java.isAssignableFrom(it.returnType)
            }
        assertThat(consumers).hasSize(1)
        assertThat(consumer(catalogue)).isNotNull
    }

    @Test
    fun `INV-AC-20572 the topics of the existing consumers are unchanged`() {
        assertThat(RecordConsumerConfiguration.REGISTRY.map { it.topic })
            .containsExactly(
                "gst.canonical.decision-trace-recorded.v1",
                "gst.platform.audit-entry-recorded.v1",
                "gst.platform.evidence-artefact-recorded.v1",
            )
        assertThat(LocatorConsumerConfiguration.TOPIC).isEqualTo("gst.canonical.canonical-version-created.v1")
        assertThat(ReturnConsumerConfiguration.GST_RETURN_RECORDED_TOPIC)
            .isEqualTo("gst.reconciliation.gst-return-recorded.v1")
    }

    @Test
    fun `INV-AC-20572 the handler bean is a SubmissionAcknowledgedHandler`() {
        val handler: SubmissionAcknowledgedHandler = configuration.submissionAcknowledgedHandler(dsl, clock)
        assertThat(handler).isNotNull
    }

    @Test
    fun `INV-AC-20572 a catalogue without the entry makes the consumer construction fail`() {
        val without = EventCatalogue(catalogue.entries().filterNot { it.eventType == type })
        assertThatThrownBy { consumer(without) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `INV-AC-20572 the look-alike topic is not accepted`() {
        val lookAlike =
            EventCatalogue(
                catalogue.entries().filterNot { it.eventType == type } +
                    CatalogueEntry(type, "gst.submission.submission-acknowledged.v2", setOf("submission-svc"), false),
            )
        assertThatThrownBy { consumer(lookAlike) }.isInstanceOf(IllegalStateException::class.java)
        val otherType =
            EventCatalogue(
                catalogue.entries().filterNot { it.eventType == type } +
                    CatalogueEntry(type + "x", topic, setOf("submission-svc"), false),
            )
        assertThatThrownBy { consumer(otherType) }.isInstanceOf(IllegalStateException::class.java)
    }
}
