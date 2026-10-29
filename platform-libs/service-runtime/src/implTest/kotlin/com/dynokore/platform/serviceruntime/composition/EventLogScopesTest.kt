package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.FakeSecretStore
import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.serviceruntime.binding.SecretStoreBindingKeySource
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.serviceruntime.pool.EventLogGate
import com.dynokore.platform.serviceruntime.pool.PermitObserver
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-12071 (INV-AC-12070): [EventLogScopes.of] on the shared-pool path (no event-log pool: the application scope
 * itself) and the separate-pool path (its own runner behind the gate, signed with the binding key).
 */
class EventLogScopesTest {
    private val tenants = TenantContextImpl()

    private val appTx = TransactionRunnerImpl(mockk<DataSource>(), tenants, null, SignedBinding.keys())
    private val app = TenantScope(tenants, appTx, appTx, "svc:test")

    private fun key(): InstallationBindingKey =
        InstallationBindingKey.of(
            FakeSecretStore().apply { put(SecretStoreBindingKeySource.CURRENT, "event-log-key-value") },
        )

    private class Recording {
        val statements = mutableListOf<String>()
        val pool: DataSource

        init {
            val connection = mockk<Connection>(relaxed = true)
            every { connection.prepareStatement(any<String>()) } answers {
                statements += firstArg<String>()
                mockk(relaxed = true)
            }
            pool = mockk<DataSource>()
            every { pool.connection } returns connection
        }
    }

    private class Counting : PermitObserver {
        var acquired = 0
        var released = 0

        override fun acquired(threadName: String) {
            acquired++
        }

        override fun released(threadName: String) {
            released++
        }
    }

    private fun runOn(scope: TenantScope) {
        tenants.bind(TenantId(UUID.randomUUID()), "user-1") { scope.transactions.inTransaction { } }
    }

    @Test
    fun `INV-AC-12070 shared pool - no event-log pool returns the application scope itself`() {
        val gate = EventLogGate()
        EventLogScopes.of(null, app, gate, key()) shouldBeSameInstanceAs app
        EventLogScopes.of(null, app, null, null) shouldBeSameInstanceAs app
    }

    @Test
    fun `INV-AC-12070 separate pool - own scope, signed on the event-log pool only, behind the gate`() {
        val eventLog = Recording()
        val application = Recording()
        val appRunner = TransactionRunnerImpl(application.pool, tenants, null, SignedBinding.keys())
        val applicationScope = TenantScope(tenants, appRunner, appRunner, "svc:test")
        val observer = Counting()
        val gate = EventLogGate().apply { observe(observer) }

        val scope = EventLogScopes.of(eventLog.pool, applicationScope, gate, key())

        (scope === applicationScope) shouldBe false
        scope.tenants shouldBe applicationScope.tenants
        scope.workloadPrincipal shouldBe applicationScope.workloadPrincipal
        scope.platform shouldBe applicationScope.platform
        (scope.transactions === applicationScope.transactions) shouldBe false
        runOn(scope)
        eventLog.statements.any { it.contains("app.binding_sig") } shouldBe true
        application.statements.none { it.contains("app.binding_sig") } shouldBe true
        observer.acquired shouldBe 1
    }

    @Test
    fun `INV-AC-12070 separate pool - works without a gate`() {
        val eventLog = Recording()
        val scope = EventLogScopes.of(eventLog.pool, app, null, key())
        (scope === app) shouldBe false
        runOn(scope)
        eventLog.statements.any { it.contains("app.binding_sig") } shouldBe true
    }

    @Test
    fun `INV-AC-12070 separate pool - without a binding key it is refused at construction`() {
        shouldThrow<IllegalArgumentException> { EventLogScopes.of(Recording().pool, app, EventLogGate(), null) }
    }

    private val service =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBindingKey()
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
                "spring.datasource.username=submission_app",
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.instance-id=submission-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=submission",
                "service-runtime.idempotency.schema=submission",
            ).withUserConfiguration(
                Tpl16ServiceCompositionTest.SubmissionBootstrap::class.java,
                Composition.TPL_18.application,
            )

    @Test
    fun `INV-AC-12070 context - event-log url absent or equal to the datasource url has no event-log bean`() {
        for (url in listOf(null, "jdbc:postgresql://localhost:5432/submission")) {
            val properties = listOfNotNull(url?.let { "gst.db.event-log-url=$it" }).toTypedArray()
            service.withPropertyValues(*properties).run { context ->
                context.startupFailure shouldBe null
                context.containsBean(ServiceDataSources.EVENT_LOG) shouldBe false
                val applicationScope = context.getBean(TenantScope::class.java)
                EventLogScopes.of(null, applicationScope, null, key()) shouldBeSameInstanceAs applicationScope
            }
        }
    }

    @Test
    fun `INV-AC-12070 context - a differing event-log url has the pool bean and its own scope`() {
        service
            .withPropertyValues("gst.db.event-log-url=jdbc:postgresql://event-log:5432/eventlog")
            .run { context ->
                context.startupFailure shouldBe null
                context.containsBean(ServiceDataSources.EVENT_LOG) shouldBe true
                val applicationScope = context.getBean(TenantScope::class.java)
                val pool = context.getBean(ServiceDataSources.EVENT_LOG, DataSource::class.java)
                val scope = EventLogScopes.of(pool, applicationScope, EventLogGate(), key())
                (scope === applicationScope) shouldBe false
            }
    }
}
