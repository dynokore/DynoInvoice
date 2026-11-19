package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.productruntime.feed.PolicyTopicReader
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A `PolicyChanged` record of the platform policy topic reaches `PolicyReplica.valueAt` through the shared policy
 * reader of product-runtime (INV-T-24769, INV-AC-24767, INV-REQ-1241). The context is built the way TPL-18 builds it
 * (P109); only the [EventLogReader] is a fake, so the reader bean, `PolicyFeed` and the replica are the production
 * ones.
 */
class PolicyTopicReaderBoundaryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, RulesConfiguration::class)
    class Bootstrap

    /** Serves the records with offset <= [visibleUpTo] of the topic they were added for, in offset order. */
    private class FakeLog : EventLogReader {
        private val records = mutableMapOf<String, MutableList<LogRecord>>()
        val topicsRead = CopyOnWriteArrayList<String>()

        @Volatile var visibleUpTo = 0L

        fun add(
            topic: String,
            record: LogRecord,
        ) {
            records.getOrPut(topic) { mutableListOf() }.add(record)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> {
            topicsRead += topic
            return records[topic]
                .orEmpty()
                .filter { it.offset > afterOffset && it.offset <= visibleUpTo }
                .sortedBy { it.offset }
                .take(limit)
        }
    }

    private val tenant = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
    private val day = LocalDate.parse("2026-10-12")
    private val e1 = PolicyScope.entity(tenant, "E1")
    private val e2 = PolicyScope.entity(tenant, "E2")
    private val eNone = PolicyScope.entity(tenant, "E3")

    private val secrets: Path =
        Files.createTempDirectory("rules-policy-reader-secrets").also { root ->
            Files.createDirectories(root.resolve("tenancy"))
            Files.writeString(root.resolve("tenancy/binding-key-current"), "test-only-binding-key-0123456789ab")
        }

    private fun record(
        offset: Long,
        scope: String,
        vp: String,
        value: String,
        raw: Boolean = false,
    ): LogRecord {
        val json = if (raw) value else "\"$value\""
        val payload =
            """{"scope":"$scope","vpId":"$vp","value":$json,""" +
                """"effectiveDate":"2026-10-01","source":"OVERRIDE"}"""
        return LogRecord(
            offset,
            EnvelopeJson().write(
                Envelope(
                    EventId(UUID.randomUUID().toString()),
                    "/gst/tenancy-svc",
                    PlatformEventTypes.POLICY_CHANGED,
                    "agg-1",
                    Instant.parse("2026-10-12T02:00:00Z"),
                    tenant,
                    EntityRef("E-001"),
                    null,
                    null,
                    null,
                    "svc-1",
                    CorrelationId("corr-1"),
                    CausationId("cause-1"),
                    payload,
                ),
            ),
        )
    }

    private fun log(topic: String = TOPIC) =
        FakeLog().also {
            it.add(topic, record(7, "ENTITY:E1", "VP-21", "ON"))
            it.add(topic, record(8, "ENTITY:E1", "VP-22", "ON"))
            it.add(topic, record(9, "ENTITY:E2", "VP-21", "ON"))
        }

    private fun runner(log: FakeLog): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(Bootstrap::class.java)
            .withBean(EventLogReader::class.java, { log })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues(
                "gst.service=rules-svc",
                "rules.access-svc-url=http://127.0.0.1:1", // required (TPL-16); nothing listens there
                "rules.fence.acknowledgement-window=PT7M", // required (INV-T-4977), no default
                "rules.dry-run.parallelism=1", // required by the dry-run capacity (INV-T-4974)
                "rules.dry-run.sample-wait=PT1S",
                "rules.dry-run.retry-after=PT2S",
                "gst.instance-id=rules-svc-0",
                "service-runtime.workload-identity.audience=rules-svc",
                "service-runtime.modules.schema=rules",
                "inv.tenancy.reference-tables=rules.pack_registry,rules.pack_entry_source,rules.pack_artefact",
                "inv.tenancy.platform-scoped-tables=rules.write_installation_setting,rules.config_purge_tenant," +
                    "rules.pack_readiness_ack,rules.tenants_with_delta",
                "spring.datasource.url=jdbc:postgresql://localhost:1/rules",
                "spring.datasource.username=rules_runtime",
                "service-runtime.pool.profile=minimal",
                "gst.bindings.secret-store.mount-root=$secrets",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
                "product-runtime.policy-reader.poll-interval=PT1H", // the test polls; the runner only starts the loop
            )

    private fun started(
        log: FakeLog,
        check: (AssertableApplicationContext) -> Unit,
    ) = runner(log).run { context ->
        context.startupFailure?.let { throw it }
        check(context)
    }

    /** The replica keeps the record's `value` as JSON text: the payload string ON is held as `"ON"` with its quotes. */
    private val on = "\"ON\""

    private fun PolicyReplica.vp(
        scope: PolicyScope,
        vp: String,
    ) = valueAt(scope, vp, day)?.value

    @Test
    fun `INV-AC-24767 a PolicyChanged record of the policy topic reaches PolicyReplica valueAt`() {
        val log = log()
        started(log) { context ->
            val replica = context.getBean(PolicyReplica::class.java)
            val reader = context.getBean(PolicyTopicReader::class.java)
            log.visibleUpTo = 7
            reader.pollOnce()
            replica.vp(e1, "VP-21") shouldBe on
            replica.vp(e1, "VP-22") shouldBe null
            log.visibleUpTo = 9
            reader.pollOnce()
            replica.vp(e1, "VP-22") shouldBe on
            replica.vp(e2, "VP-21") shouldBe on
            replica.vp(e2, "VP-22") shouldBe null
            replica.vp(eNone, "VP-21") shouldBe null
            log.topicsRead.toSet() shouldBe setOf(TOPIC)
        }
    }

    @Test
    fun `INV-AC-24767 a record of the look-alike topic v2 does not reach the replica`() {
        val log = log("gst.platform.policy-changed.v2")
        started(log) { context ->
            log.visibleUpTo = 9
            context.getBean(PolicyTopicReader::class.java).pollOnce()
            context.getBean(PolicyReplica::class.java).vp(e1, "VP-21") shouldBe null
        }
    }

    @Test
    fun `INV-AC-24767 the context starts with exactly one bean of type PolicyFeed`() {
        started(log()) { context ->
            context.getBeansOfType(PolicyFeed::class.java).size shouldBe 1
            context.getBeansOfType(PolicyTopicReader::class.java).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-25208 a VP-21 option list ON record turns the election on for its entity only`() {
        val onList = """[{"key":"ON"}]"""
        val log = FakeLog().also { it.add(TOPIC, record(7, "ENTITY:E1", "VP-21", onList, raw = true)) }
        started(log) { context ->
            val replica = context.getBean(PolicyReplica::class.java)
            val election = context.getBean(VoluntaryOverSubmission::class.java)
            log.visibleUpTo = 7
            context.getBean(PolicyTopicReader::class.java).pollOnce()
            replica.vp(e1, "VP-21") shouldBe onList
            election.isOn(tenant, "E1", TransactionDate(day)) shouldBe true
            election.isOn(tenant, "E2", TransactionDate(day)) shouldBe false
        }
    }

    @Test
    fun `INV-AC-25208 a VP-21 record whose value is the string ON leaves the election off`() {
        val log = FakeLog().also { it.add(TOPIC, record(7, "ENTITY:E1", "VP-21", "ON")) }
        started(log) { context ->
            log.visibleUpTo = 7
            context.getBean(PolicyTopicReader::class.java).pollOnce()
            context.getBean(PolicyReplica::class.java).vp(e1, "VP-21") shouldBe on
            context.getBean(VoluntaryOverSubmission::class.java).isOn(tenant, "E1", TransactionDate(day)) shouldBe false
        }
    }

    private companion object {
        const val TOPIC = "gst.platform.policy-changed.v1"
    }
}
