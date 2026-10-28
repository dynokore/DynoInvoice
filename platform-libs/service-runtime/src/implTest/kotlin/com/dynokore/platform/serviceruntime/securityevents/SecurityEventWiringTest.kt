package com.dynokore.platform.serviceruntime.securityevents

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.auditlog.StagedAuditEntry
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.BindingAutoConfiguration
import com.dynokore.platform.serviceruntime.binding.TelemetryExport
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties
import java.util.UUID
import javax.sql.DataSource

/** INV-T-10969: the mapping, the commit boundary and the configuration checks, through the Spring wiring (P109). */
class SecurityEventWiringTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val tenants = TenantContextImpl()
    private val context = mockk<EventContext>(relaxed = true).also { every { it.tenant } returns tenant }

    /** The real runner over a relaxed connection: the hook under test is the runner's own `afterCommit`. */
    private val connection = mockk<Connection>(relaxed = true)
    private val dataSource = mockk<DataSource>().also { every { it.connection } returns connection }
    private val realRunner = TransactionRunnerImpl(dataSource, tenants, null, SignedBinding.keys())

    /** The real audit-log writer over an outbox that stamps nothing. */
    private val outbox =
        mockk<OutboxWriter>().also {
            every { it.append(any(), any()) } returns
                listOf(mockk(relaxed = true))
        }
    private val realWriter = OutboxAuditWriter(outbox)

    private fun runner(
        stub: CollectorStub,
        vararg properties: String,
        withExport: Boolean = true,
        writer: AuditWriter = realWriter,
    ) = ApplicationContextRunner()
        .withUserConfiguration(SecurityEventsConfiguration::class.java)
        .withBean(AuditWriter::class.java, { writer })
        .withBean(TransactionRunner::class.java, { realRunner })
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        .let { if (withExport) it.withBean(TelemetryExport::class.java, { TelemetryExport { stub.baseUrl } }) else it }
        .withPropertyValues(*properties)

    private fun inTransaction(
        ctx: org.springframework.context.ApplicationContext,
        block: () -> Unit,
    ) = tenants.bind(tenant, "svc:test") { ctx.getBean(TransactionRunner::class.java).inTransaction(block) }

    private fun await(
        stub: CollectorStub,
        count: Int,
    ) {
        val end = System.nanoTime() + 5_000_000_000L
        while (stub.received.size < count && System.nanoTime() < end) Thread.sleep(10)
        Thread.sleep(150)
    }

    @Test
    fun `a mapped audit action raises one record after commit with the entry actor`() {
        CollectorStub { 200 }.use { stub ->
            runner(stub).run { ctx ->
                inTransaction(ctx) {
                    ctx
                        .getBean(
                            AuditWriter::class.java,
                        ).record(AuditEntry("user-9", "acknowledgeControlRelaxation", "s"), context)
                    stub.received.size shouldBe 0 // not before commit
                }
                await(stub, 1)
                stub.received.size shouldBe 1
                stub.received.single().body shouldContain "\"user-9\""
                stub.received.single().body shouldContain "control_relaxation"
                stub.received.single().body shouldContain tenant.toString()
            }
        }
    }

    @Test
    fun `the transaction runner bean keeps its class and is not a proxy`() {
        CollectorStub { 200 }.use { stub ->
            runner(stub).run { ctx ->
                val bean = ctx.getBean(TransactionRunner::class.java)
                bean.shouldBeInstanceOf<TransactionRunnerImpl>()
                AopUtils.isAopProxy(bean) shouldBe false
            }
        }
    }

    @Test
    fun `a rolled-back transaction emits nothing`() {
        CollectorStub { 200 }.use { stub ->
            runner(stub).run { ctx ->
                shouldThrow<IllegalStateException> {
                    inTransaction(ctx) {
                        ctx
                            .getBean(
                                AuditWriter::class.java,
                            ).record(AuditEntry("u", "acknowledgeControlRelaxation", "s"), context)
                        error("rollback")
                    }
                }
                await(stub, 1)
                stub.received.size shouldBe 0
            }
        }
    }

    @Test
    fun `an unmapped action and look-alike names emit nothing`() {
        CollectorStub { 200 }.use { stub ->
            runner(stub).run { ctx ->
                val audit = ctx.getBean(AuditWriter::class.java)
                inTransaction(ctx) {
                    listOf(
                        "setPolicy",
                        "acknowledgeControlRelaxationX",
                        "acknowledgeControlRelaxatio",
                        "acknowledge",
                        "AcknowledgeControlRelaxation",
                        "ACKNOWLEDGECONTROLRELAXATION",
                        " acknowledgeControlRelaxation",
                        "acknowledgeControlRelaxation ",
                    ).forEach { audit.record(AuditEntry("u", it, "s"), context) }
                }
                await(stub, 1)
                stub.received.size shouldBe 0
            }
        }
    }

    @Test
    fun `a service entry merges with the shipped default by key and never replaces it`() {
        CollectorStub { 200 }.use { stub ->
            runner(stub, "gst.security-events.mapping[unlockBreakGlass]=break_glass").run { ctx ->
                ctx.getBean(SecurityEventProperties::class.java).mapping shouldBe
                    mapOf("acknowledgeControlRelaxation" to "control_relaxation", "unlockBreakGlass" to "break_glass")
                val audit = ctx.getBean(AuditWriter::class.java)
                inTransaction(ctx) {
                    audit.record(AuditEntry("u", "unlockBreakGlass", "s"), context)
                    audit.record(AuditEntry("u", "acknowledgeControlRelaxation", "s"), context)
                    audit.record(AuditEntry("u", "unlockBreakGlass-x", "s"), context)
                }
                await(stub, 2)
                stub.received.size shouldBe 2
                stub.received.map { it.body }.count { it.contains("break_glass") } shouldBe 1
                stub.received.map { it.body }.count { it.contains("control_relaxation") } shouldBe 1
            }
        }
    }

    @Test
    fun `a key set through an environment variable is lower-cased and does not match a camel-case action`() {
        CollectorStub { 200 }.use { stub ->
            runner(stub)
                .withInitializer { ctx ->
                    ctx.environment.propertySources.addFirst(
                        SystemEnvironmentPropertySource(
                            "systemEnvironment",
                            mapOf("GST_SECURITYEVENTS_MAPPING_UNLOCKBREAKGLASS" to "break_glass"),
                        ),
                    )
                }.run { ctx ->
                    ctx.getBean(SecurityEventProperties::class.java).mapping.keys shouldBe
                        setOf("acknowledgeControlRelaxation", "unlockbreakglass")
                    val audit = ctx.getBean(AuditWriter::class.java)
                    inTransaction(ctx) { audit.record(AuditEntry("u", "unlockBreakGlass", "s"), context) }
                    await(stub, 1)
                    stub.received.size shouldBe 0
                }
        }
    }

    @Test
    fun `a staged entry passes through unchanged and the decorator stays a stager`() {
        CollectorStub { 200 }.use { stub ->
            val entry = AuditEntry("u", "acknowledgeControlRelaxation", "s")
            val expected = StagedAuditEntry(mockk(relaxed = true), entry, null)
            val stagingWriter =
                object : AuditWriter, AuditStager {
                    override fun record(
                        entry: AuditEntry,
                        context: EventContext,
                    ): Envelope = mockk(relaxed = true)

                    override fun stage(
                        entry: AuditEntry,
                        context: EventContext,
                    ): StagedAuditEntry = expected
                }
            runner(stub, writer = stagingWriter).run { ctx ->
                val audit = ctx.getBean(AuditWriter::class.java)
                audit.shouldBeInstanceOf<AuditStager>()
                audit.stage(entry, context) shouldBeSameInstanceAs expected
                await(stub, 1)
                stub.received.size shouldBe 0
            }
        }
    }

    @Test
    fun `invalid mapping or allowed list stops the context naming the property`() {
        CollectorStub { 200 }.use { stub ->
            fun failure(
                vararg p: String,
                withExport: Boolean = true,
                isolated: Boolean = false,
            ): String {
                var message = ""
                val base =
                    runner(stub, *p, withExport = withExport).let {
                        if (!isolated) {
                            it
                        } else {
                            it
                                .withConfiguration(AutoConfigurations.of(BindingAutoConfiguration::class.java))
                                .withInitializer { context ->
                                    context.environment.propertySources
                                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                                }
                        }
                    }
                base.run { ctx ->
                    message =
                        generateSequence<Throwable>(ctx.startupFailure!!) { it.cause }
                            .joinToString(" | ") { it.message ?: "" }
                }
                return message
            }

            failure("gst.security-events.mapping[a]=other") shouldContain "gst.security-events.mapping[a]"
            failure("gst.security-events.mapping[ ]=sign_in") shouldContain "gst.security-events.mapping"
            failure("gst.security-events.allowed-attributes=") shouldContain "gst.security-events.allowed-attributes"
            failure("gst.security-events.class-attribute=x.class") shouldContain "gst.security-events.class-attribute"
            // The binding auto-configuration is in the context, so the start failure holds whatever the build sets
            // (INV-Q-11993): the system properties are removed from the environment.
            val noExport = failure(withExport = false, isolated = true)
            noExport shouldContain "TelemetryExport"
            noExport shouldContain "gst.bindings.telemetry-export"
        }
    }

    @Test
    fun `the packaged defaults equal the literals of INV-T-10969`() {
        val file = Path.of("src/impl/resources/service-runtime-security-events-defaults.properties")
        val p = Properties().also { props -> Files.newBufferedReader(file).use { props.load(it) } }
        p.getProperty("gst.security-events.class-attribute") shouldBe "gst.security.class"
        p.getProperty("gst.security-events.allowed-attributes") shouldBe "gst.security.class,user.id,gst.tenant.id"
        p.getProperty("gst.security-events.queue-capacity") shouldBe "1000"
        p.getProperty("gst.security-events.retry-max-attempts") shouldBe "3"
        p.getProperty("gst.security-events.retry-backoff-millis") shouldBe "1000"
        p
            .stringPropertyNames()
            .filter {
                it.startsWith(
                    "gst.security-events.mapping",
                )
            }.associateWith(p::getProperty) shouldBe
            mapOf("gst.security-events.mapping[acknowledgeControlRelaxation]" to "control_relaxation")
        // the defaults are not literals of Kotlin source
        Files.walk(Path.of("src/impl/kotlin/com/dynokore/platform/serviceruntime/securityevents")).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach {
                Files.readString(it).contains("\"gst.security.class\"") shouldBe false
            }
        }
    }

    @Test
    fun `the five classes keep their wire values in order`() {
        SecurityClass.entries.map { it.name to it.wireValue } shouldBe
            listOf(
                "SIGN_IN" to "sign_in",
                "ELEVATION" to "elevation",
                "BREAK_GLASS" to "break_glass",
                "CREDENTIAL_USE" to "credential_use",
                "CONTROL_RELAXATION" to "control_relaxation",
            )
    }

    @Test
    fun `SECURITY-EVENTS md has exactly five rows, one per class in enum order`() {
        val rows =
            Files
                .readAllLines(Path.of("SECURITY-EVENTS.md"))
                .filter { it.startsWith("| `") }
                .map { it.split("|")[1].trim().trim('`') }
        rows shouldBe SecurityClass.entries.map { it.wireValue }
    }
}
