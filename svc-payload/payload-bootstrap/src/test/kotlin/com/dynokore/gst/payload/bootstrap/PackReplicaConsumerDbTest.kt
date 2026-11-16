package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.packs.JooqMdePackReplica
import com.dynokore.gst.payload.adapters.packs.RulePackEventsHandler
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-23578 through the real inbox consumers on the tables of schema `payload` over PostgreSQL (P109). Only the
 * event-log read is a list in memory.
 */
class PackReplicaConsumerDbTest {
    private val actor = "svc:payload-svc"
    private val json = EnvelopeJson()

    private class Log : EventLogReader {
        private val records = mutableMapOf<String, MutableList<LogRecord>>()

        fun add(
            topic: String,
            envelopeJson: String,
        ) {
            val list = records.getOrPut(topic) { mutableListOf() }
            list += LogRecord(list.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records[topic].orEmpty().filter { it.offset > afterOffset }.take(limit)
    }

    private inner class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val replica: JooqMdePackReplica = context.getBean(JooqMdePackReplica::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val consumerBeans: Map<String, JdbcInboxConsumer> = context.getBeansOfType(JdbcInboxConsumer::class.java)
        val log = Log()
        private val consumerName = PackReplicaConfiguration.CONSUMER + "-" + UUID.randomUUID()

        private fun consumer(
            type: String,
            topic: String,
        ) = JdbcInboxConsumer(
            ConsumerSubscription(consumerName, topic, PackReplicaConfiguration.SCHEMA),
            catalogue,
            log,
            scope,
            dsl,
            RulePackEventsHandler(type, replica),
            RebuildRequiredSink { },
            Clock.systemUTC(),
        )

        fun deliver(
            tenant: TenantId,
            staged: Boolean,
            data: String,
        ): PollResult {
            val type = if (staged) RulePackEventsHandler.STAGED_TYPE else RulePackEventsHandler.ACTIVATED_TYPE
            val topic = if (staged) RulePackEventsHandler.STAGED_TOPIC else RulePackEventsHandler.ACTIVATED_TOPIC
            log.add(topic, json.write(envelope(tenant, type, data)))
            return consumer(type, topic).pollOnce()
        }

        fun <T> tx(
            tenant: TenantId,
            body: () -> T,
        ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }
    }

    private fun envelope(
        tenant: TenantId,
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/rules-svc",
        type = type,
        subject = "P-MDE",
        time = Instant.parse("2026-11-09T08:00:00Z"),
        tenant = tenant,
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "rules-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun staged(
        version: String,
        contents: String,
        type: String = "MDE",
        id: String = "P-MDE",
    ) = """{"packIdentifier":"$id","packType":"$type","version":"$version","effectiveFrom":"2026-12-01",""" +
        """"minEngineVersion":"1.0.0","artefact":{"manifest":{},"checksums":{},"signature":"s","contents":$contents}}"""

    private fun activated(
        version: String,
        id: String = "P-MDE",
    ) = """{"packIdentifier":"$id","version":"$version","effectiveFrom":"2026-12-01"}"""

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        PayloadContext.online(dir).run { context -> Boundary(context).block(TenantId(UUID.randomUUID())) }
    }

    private fun rows(tenant: TenantId): Int =
        PayloadDb.superuser().use { c ->
            c.prepareStatement("select count(*) from payload.mde_pack_replica where tenant_id = ?").use { st ->
                st.setObject(1, tenant.value)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun activeRows(tenant: TenantId): Int =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement("select count(*) from payload.mde_pack_replica where tenant_id = ? and active")
                .use { st ->
                    st.setObject(1, tenant.value)
                    st.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun identities(tenant: TenantId): Int =
        PayloadDb.superuser().use { c ->
            c.prepareStatement("select count(*) from payload.pack_identity where tenant_id = ?").use { st ->
                st.setObject(1, tenant.value)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private val applied = PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
    private val ignored = PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)

    @Test
    fun `INV-AC-23578 staged then activated is held`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"offNetworkEndpoint":{"prefix":"C5UID"}}""")) shouldBe applied
        deliver(t1, true, staged("2.0.0", """{"offNetworkEndpoint":{"prefix":"C6UID"}}""")) shouldBe applied
        tx(t1) { replica.active() } shouldBe null
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        deliver(t1, false, activated("2.0.0")) shouldBe applied
        val active = tx(t1) { replica.active() }
        active?.version shouldBe "2.0.0"
        active?.contentsJson shouldBe """{"offNetworkEndpoint": {"prefix": "C6UID"}}"""
        tx(t1) { replica.contentsOf("P-MDE", "1.0.0") } shouldBe """{"offNetworkEndpoint": {"prefix": "C5UID"}}"""
        rows(t1) shouldBe 2
        activeRows(t1) shouldBe 1
        consumerBeans.keys.containsAll(listOf("rulePackStagedConsumer", "rulePackActivatedConsumer")) shouldBe true
    }

    @Test
    fun `INV-AC-23578 redelivery changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val first = staged("1.0.0", """{"a":1}""")
        deliver(t1, true, first) shouldBe applied
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        deliver(t1, true, first) shouldBe ignored
        deliver(t1, false, activated("1.0.0")) shouldBe ignored
        // the same version with other contents is refused (dead letter), never overwritten
        deliver(t1, true, staged("1.0.0", """{"a":2}""")) shouldBe
            PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        val active = tx(t1) { replica.active() }
        active?.version shouldBe "1.0.0"
        active?.contentsJson shouldBe """{"a": 1}"""
        rows(t1) shouldBe 1
        activeRows(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-23578 other pack type is ignored`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""", type = "Scope")) shouldBe ignored
        deliver(t1, true, staged("1.0.0", """{"a":1}""", type = "mde", id = "P-LOW")) shouldBe ignored
        rows(t1) shouldBe 0
        identities(t1) shouldBe 2
        tx(t1) { replica.active() } shouldBe null
    }

    @Test
    fun `INV-AC-23578 activation before staging is retried`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        // the consumer rolls the record back and rethrows: the offset stays, the inbox retries it
        shouldThrow<IllegalStateException> { deliver(t1, false, activated("9.9.9")) }
        shouldThrow<IllegalStateException> { tx(t1) { replica.activate("P-MDE", "9.9.9") } }
        rows(t1) shouldBe 1
        activeRows(t1) shouldBe 0
    }

    @Test
    fun `INV-AC-23578 other tenant reads nothing`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val t2 = TenantId(UUID.randomUUID())
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        tx(t1) { replica.active() }?.version shouldBe "1.0.0"
        tx(t2) { replica.active() } shouldBe null
        tx(t2) { replica.contentsOf("P-MDE", "1.0.0") } shouldBe null
        shouldThrow<IllegalStateException> { tx(t2) { replica.activate("P-MDE", "1.0.0") } }
        // the tenant predicate of the statement holds on its own: a session bound to t1 reads nothing for t2
        val asT2 = JooqMdePackReplica(dsl) { t2.value }
        tx(t1) { asT2.active() } shouldBe null
        tx(t1) { asT2.contentsOf("P-MDE", "1.0.0") } shouldBe null
    }

    @Test
    fun `INV-AC-23578 activation of another pack type is ignored`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, true, staged("1.0.0", """{"s":1}""", type = "Scope", id = "P-SCOPE")) shouldBe ignored
        deliver(t1, false, activated("1.0.0", id = "P-SCOPE")) shouldBe ignored
        activeRows(t1) shouldBe 0
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        deliver(t1, false, activated("1.0.0", id = "P-SCOPE")) shouldBe ignored
        tx(t1) { replica.active() }?.packIdentifier shouldBe "P-MDE"
        activeRows(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-23578 equal version of another pack is not activated`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, true, staged("1.0.0", """{"b":1}""", id = "P-MDE-2")) shouldBe applied
        deliver(t1, false, activated("1.0.0", id = "P-MDE-2")) shouldBe applied
        val active = tx(t1) { replica.active() }
        active?.packIdentifier shouldBe "P-MDE-2"
        active?.contentsJson shouldBe """{"b": 1}"""
        activeRows(t1) shouldBe 1
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        tx(t1) { replica.active() }?.packIdentifier shouldBe "P-MDE"
        activeRows(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-23578 activation of an unstaged version is retried`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        shouldThrow<IllegalStateException> { deliver(t1, false, activated("2.0.0")) }
        // an unknown identifier is held for retry too (the handler alone: the failed record above stays queued)
        val handler = RulePackEventsHandler(RulePackEventsHandler.ACTIVATED_TYPE, replica)
        shouldThrow<IllegalStateException> {
            tx(t1) { handler.handle(envelope(t1, RulePackEventsHandler.ACTIVATED_TYPE, activated("1.0.0", id = "P-UNKNOWN"))) }
        }
        activeRows(t1) shouldBe 0
        rows(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-23578 identity type is never overwritten`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, true, staged("1.0.0", """{"a":1}""", type = "Scope")) shouldBe
            PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        tx(t1) { replica.typeOf("P-MDE") } shouldBe "MDE"
        deliver(t1, true, staged("3.0.0", """{"a":3}""", type = "Scope")) shouldBe
            PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        rows(t1) shouldBe 1
        identities(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-23578 a handler refuses an event of another type`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val handler = RulePackEventsHandler(RulePackEventsHandler.ACTIVATED_TYPE, replica)
        shouldThrow<com.dynokore.platform.outboxandqueue.ContractViolation> {
            handler.handle(envelope(t1, RulePackEventsHandler.STAGED_TYPE, staged("1.0.0", "{}")))
        }
        rows(t1) shouldBe 0
    }
}
