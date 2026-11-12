package com.dynokore.gst.evidence.bootstrap

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
 * INV-AC-20568: the bootstrap configuration consumes PayloadReady and SubmissionDespatched with one subscription and
 * one inbox consumer each; the database-backed beans are test doubles, the configuration methods are called directly.
 */
class SealingWiringTest {
    private val readyType = "com.dynokore.gst.payload.PayloadReady.v1"
    private val readyTopic = "gst.payload.payload-ready.v1"
    private val despatchedType = "com.dynokore.gst.submission.SubmissionDespatched.v1"
    private val despatchedTopic = "gst.submission.submission-despatched.v1"
    private val configuration = SealingConsumerConfiguration()
    private val clock = Clock.systemUTC()
    private val dsl = DSL.using(SQLDialect.POSTGRES)
    private val catalogue = EventConfiguration().eventCatalogue()

    private fun <T> stub(
        type: Class<T>,
        vararg more: Class<*>,
    ): T {
        val proxy =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type, *more)) { _, method, _ ->
                throw UnsupportedOperationException("test double: ${method.name}")
            }
        return type.cast(proxy)
    }

    private fun scope(): TenantScope {
        val runner = stub(TransactionRunner::class.java, CurrentConnection::class.java)
        return TenantScope(stub(TenantContext::class.java), runner, runner as CurrentConnection, "evidence-svc")
    }

    private val sealer = configuration.transmittedPayloadSealer(dsl, clock, configuration.sealSignals())

    private fun ready(catalogue: EventCatalogue): JdbcInboxConsumer =
        configuration.payloadReadyConsumer(
            catalogue,
            stub(EventLogReader::class.java),
            scope(),
            RebuildRequiredSink { },
            clock,
            dsl,
            configuration.payloadReadyHandler(sealer),
        )

    private fun despatched(catalogue: EventCatalogue): JdbcInboxConsumer =
        configuration.submissionDespatchedConsumer(
            catalogue,
            stub(EventLogReader::class.java),
            scope(),
            RebuildRequiredSink { },
            clock,
            dsl,
            configuration.submissionDespatchedHandler(sealer, dsl),
        )

    @Test
    fun `INV-AC-20568 the catalogue lists the two entries on their topics`() {
        val r = catalogue.find(readyType)!!
        assertThat(r.eventType).isEqualTo(readyType)
        assertThat(r.topic).isEqualTo(readyTopic)
        assertThat(r.producers).isEqualTo(setOf("payload-svc"))
        assertThat(r.contentTopic).isTrue()
        assertThat(r.recordTopic).isFalse()
        val d = catalogue.find(despatchedType)!!
        assertThat(d.eventType).isEqualTo(despatchedType)
        assertThat(d.topic).isEqualTo(despatchedTopic)
        assertThat(d.producers).isEqualTo(setOf("submission-svc"))
        assertThat(d.contentTopic).isFalse()
        assertThat(catalogue.topic(readyTopic)).isSameAs(r)
        assertThat(catalogue.topic(despatchedTopic)).isSameAs(d)
        assertThat(catalogue.topic("gst.payload.payload-ready.v2")).isNull()
        assertThat(catalogue.topic("gst.submission.submission-despatched.v2")).isNull()
    }

    @Test
    fun `INV-AC-20568 one subscription and one inbox consumer exist per topic`() {
        val s1 = SealingConsumerConfiguration.PAYLOAD_READY_SUBSCRIPTION
        val s2 = SealingConsumerConfiguration.DESPATCHED_SUBSCRIPTION
        assertThat(s1.topic).isEqualTo(readyTopic)
        assertThat(s2.topic).isEqualTo(despatchedTopic)
        listOf(s1, s2).forEach {
            assertThat(it.consumer).isEqualTo(LocatorConsumerConfiguration.CONSUMER)
            assertThat(it.schema).isEqualTo("evidence")
        }
        val consumers =
            SealingConsumerConfiguration::class.java.declaredMethods.filter {
                java.lang.reflect.Modifier
                    .isPublic(it.modifiers) &&
                    JdbcInboxConsumer::class.java.isAssignableFrom(it.returnType)
            }
        assertThat(consumers).hasSize(2)
        assertThat(ready(catalogue)).isNotNull
        assertThat(despatched(catalogue)).isNotNull
    }

    @Test
    fun `INV-AC-20568 the topics of the existing consumers are unchanged`() {
        assertThat(RecordConsumerConfiguration.REGISTRY.map { it.topic })
            .containsExactly(
                "gst.canonical.decision-trace-recorded.v1",
                "gst.platform.audit-entry-recorded.v1",
                "gst.platform.evidence-artefact-recorded.v1",
            )
        assertThat(LocatorConsumerConfiguration.TOPIC).isEqualTo("gst.canonical.canonical-version-created.v1")
        assertThat(ReturnConsumerConfiguration.GST_RETURN_RECORDED_TOPIC)
            .isEqualTo("gst.reconciliation.gst-return-recorded.v1")
        assertThat(AcknowledgementConsumerConfiguration.TOPIC).isEqualTo("gst.submission.submission-acknowledged.v1")
    }

    @Test
    fun `INV-AC-20568 a catalogue without the entry makes the consumer construction fail`() {
        val noReady = EventCatalogue(catalogue.entries().filterNot { it.eventType == readyType })
        assertThatThrownBy { ready(noReady) }.isInstanceOf(IllegalStateException::class.java)
        val noDespatched = EventCatalogue(catalogue.entries().filterNot { it.eventType == despatchedType })
        assertThatThrownBy { despatched(noDespatched) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `INV-AC-20568 look-alike topics and types are not accepted`() {
        fun without(type: String) = catalogue.entries().filterNot { it.eventType == type }
        val readyV2 =
            EventCatalogue(
                without(readyType) +
                    CatalogueEntry(readyType, "gst.payload.payload-ready.v2", setOf("payload-svc"), true),
            )
        assertThatThrownBy { ready(readyV2) }.isInstanceOf(IllegalStateException::class.java)
        val despatchedV2 =
            EventCatalogue(
                without(despatchedType) +
                    CatalogueEntry(
                        despatchedType,
                        "gst.submission.submission-despatched.v2",
                        setOf("submission-svc"),
                        false,
                    ),
            )
        assertThatThrownBy { despatched(despatchedV2) }.isInstanceOf(IllegalStateException::class.java)
        val otherType =
            EventCatalogue(without(readyType) + CatalogueEntry(readyType + "x", readyTopic, setOf("payload-svc"), true))
        assertThatThrownBy { ready(otherType) }.isInstanceOf(IllegalStateException::class.java)
    }
}
