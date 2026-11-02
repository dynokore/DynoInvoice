package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.util.UUID

/** The decorator acts on the key VP-53 only, and a refused write publishes nothing (ruling INV-Q-12262). */
class GstSchemeEventDecoratorTest {
    private class Refusing(
        val seen: MutableList<VariationPointKey>,
    ) : PolicyWriter by proxy() {
        override fun setPolicy(command: SetPolicyCommand): PolicyChange {
            seen += command.key
            throw PolicyRefused(PolicyRefusal.UNKNOWN_OPTION, command.key, "refused for the test")
        }
    }

    private val published = mutableListOf<EntityProfileChange>()
    private var historyReads = 0
    private val seen = mutableListOf<VariationPointKey>()
    private val tenant = TenantId(UUID.randomUUID())

    private val history: PolicyHistory =
        proxy { _, _ ->
            historyReads++
            emptyList<Any>()
        }

    private val dependencies =
        SchemeEventDependencies(
            history,
            proxy<EntityProfileChangePublisher> { _, args -> published += args!![0] as EntityProfileChange },
            proxy { _, _ -> error("the structure is not touched") },
            proxy { _, _ -> error("the statuses are not touched") },
            { error("the extras are not read") },
            proxy { name, _ -> if (name.startsWith("current-")) tenant.value else null },
        )

    private val decorator = GstSchemeEventDecorator(Refusing(seen)) { dependencies }

    private fun command(
        key: String,
        level: ScopeLevel = ScopeLevel.ENTITY,
    ): SetPolicyCommand =
        SetPolicyCommand(
            VariationPointKey(key),
            PolicyScope.entity(tenant, "ENT-1"),
            level,
            listOf(SelectedOption("OPTION")),
            LocalDate.of(2026, 11, 2),
            "test",
            Actor("user:test", setOf("entity.admin")),
            CorrelationId("corr-1"),
            CausationId("cause-1"),
        )

    private fun attempt(command: SetPolicyCommand): Throwable? =
        runCatching { decorator.setPolicy(command) }.exceptionOrNull()

    @Test
    fun `a refused VP-53 write reaches the delegate once and publishes nothing`() {
        (attempt(command("VP-53")) as PolicyRefused).refusal shouldBe PolicyRefusal.UNKNOWN_OPTION
        seen shouldBe listOf(VariationPointKey("VP-53"))
        published.shouldBeEmpty()
    }

    @Test
    fun `the look-alike key VP-530 and the other keys go to the delegate unread and publish nothing`() {
        val keys = listOf("VP-530", "VP-21", "VP-52", "VP-54", "VP-22")
        keys.forEach { attempt(command(it)) }
        seen.map { it.value } shouldBe keys
        historyReads shouldBe 0
        published.shouldBeEmpty()
    }

    @Test
    fun `a VP-53 write at another scope level is passed on without reading the history`() {
        attempt(command("VP-53", ScopeLevel.TENANT))
        seen.size shouldBe 1
        historyReads shouldBe 0
        published.shouldBeEmpty()
    }

    @Test
    fun `lower case and short keys cannot be built as variation point keys`() {
        listOf("vp-53", "VP-5").forEach { id ->
            (runCatching { VariationPointKey(id) }.exceptionOrNull() is IllegalArgumentException) shouldBe true
        }
    }
}

/** An interface instance whose methods go to [answer] (name, args); the default answers null. */
private inline fun <reified T : Any> proxy(noinline answer: (String, Array<Any?>?) -> Any? = { _, _ -> null }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        answer(method.name, args)
    } as T
