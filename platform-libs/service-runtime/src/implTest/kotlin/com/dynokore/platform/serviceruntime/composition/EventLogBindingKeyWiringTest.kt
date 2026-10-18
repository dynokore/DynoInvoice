package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.FakeSecretStore
import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.serviceruntime.binding.SecretStoreBindingKeySource
import com.dynokore.platform.serviceruntime.pool.EventLogGate
import com.dynokore.platform.serviceruntime.pool.LaneGateHolder
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-5360: the event-log pool's own runner, built in OutboxAndQueueWiring, signs the binding with the key from the
 * provider (the application runner is covered by BindingKeyWiringTest). The statements the runner prepares on a
 * recording pool show whether `app.binding_sig` is set.
 */
class EventLogBindingKeyWiringTest {
    private val tenants = TenantContextImpl()
    private val gates = DefaultListableBeanFactory()

    private fun statements(key: InstallationBindingKey?): List<String> {
        val texts = mutableListOf<String>()
        val connection = mockk<Connection>(relaxed = true)
        every { connection.prepareStatement(any<String>()) } answers {
            texts += firstArg<String>()
            mockk(relaxed = true)
        }
        val pool = mockk<DataSource>()
        every { pool.connection } returns connection
        val provider = mockk<ObjectProvider<InstallationBindingKey>>()
        every { provider.getIfAvailable() } returns key
        val wiring =
            OutboxAndQueueWiring(
                gates.getBeanProvider(LaneGateHolder::class.java),
                gates.getBeanProvider(EventLogGate::class.java),
                provider,
            )
        val appTx = TransactionRunnerImpl(mockk<DataSource>(), tenants)
        val app = TenantScope(tenants, appTx, appTx, "svc:test")
        val eventLog = mockk<ObjectProvider<DataSource>>()
        every { eventLog.getIfAvailable() } returns pool
        // the private method both event-log beans (presence, purge, reader) build their scope with
        val method =
            OutboxAndQueueWiring::class.java
                .getDeclaredMethod("eventLogScopeOf", ObjectProvider::class.java, TenantScope::class.java)
                .also { it.isAccessible = true }
        val scope = method.invoke(wiring, eventLog, app) as TenantScope
        (scope === app) shouldBe false
        tenants.bind(TenantId(UUID.randomUUID()), "user-1") { scope.transactions.inTransaction { } }
        return texts
    }

    @Test
    fun `the event-log runner signs the binding with the key source`() {
        val store = FakeSecretStore().apply { put(SecretStoreBindingKeySource.CURRENT, "event-log-key-value") }
        statements(InstallationBindingKey.of(store)).any { it.contains("app.binding_sig") } shouldBe true
    }

    @Test
    fun `without a key the event-log runner is unsigned`() {
        statements(InstallationBindingKey(null)).filter { it.contains("app.binding_sig") }.shouldBeEmpty()
        statements(null).filter { it.contains("app.binding_sig") }.shouldBeEmpty()
    }
}
