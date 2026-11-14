package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.adapters.policy.PolicyGateModes
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.platform.configresolver.ElectionRequired
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The gate-mode adapter against a fake resolver (INV-AC-22488). */
class PolicyGateModesTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val day = LocalDate.of(2026, 4, 29)
    private val calls = mutableListOf<Triple<String, PolicyScope, LocalDate>>()
    private var keys: List<String> = listOf("ADVISORY")
    private var failure: (() -> RuntimeException)? = null

    private fun <T : Any> create(
        type: Class<T>,
        vararg args: Any,
    ): T =
        type.declaredConstructors
            .first { c ->
                c.parameterCount == args.size && c.parameterTypes.none { it.simpleName == "DefaultConstructorMarker" }
            }.also { it.isAccessible = true }
            .newInstance(*args)
            .let(type::cast)

    /** The real type refuses an empty selection in its constructor, so the defensive case is built without it. */
    private fun withoutOptions(
        key: String,
        at: LocalDate,
    ): ResolvedSetting {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val setting = unsafe.allocateInstance(ResolvedSetting::class.java) as ResolvedSetting
        mapOf("key" to key, "at" to at, "selected" to emptyList<SelectedOption>()).forEach { (name, value) ->
            ResolvedSetting::class.java
                .getDeclaredField(name)
                .also { it.isAccessible = true }
                .set(setting, value)
        }
        return setting
    }

    private val reader =
        object : PolicyReader {
            override fun resolve(
                key: VariationPointKey,
                scope: PolicyScope,
                at: LocalDate,
            ): ResolvedSetting {
                calls += Triple(key.value, scope, at)
                failure?.let { throw it() }
                if (keys.isEmpty()) return withoutOptions(key.value, at)
                return create(
                    ResolvedSetting::class.java,
                    key.value,
                    at,
                    keys.map { SelectedOption(it, null) },
                    PolicySource.CatalogueDefault,
                    emptyList<Any>(),
                )
            }
        }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = "svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val adapter = PolicyGateModes(reader, tenants)

    private fun modeOf(vararg selected: String): GateMode {
        keys = selected.toList()
        return adapter.modeAt("E", day)
    }

    @Test
    fun `INV-AC-22488 ADVISORY and BLOCKING map to the modes`() {
        modeOf("ADVISORY") shouldBe GateMode.ADVISORY
        modeOf("BLOCKING") shouldBe GateMode.BLOCKING
        calls.first().first shouldBe VariationPoint.VP_14.id
        calls.first().second.entity shouldBe "E"
        calls.first().second.tenant shouldBe tenant
    }

    @Test
    fun `INV-AC-22488 the date passed in reaches the resolver unchanged`() {
        modeOf("ADVISORY")
        adapter.modeAt("E", LocalDate.of(2024, 1, 31))
        calls.map { it.third } shouldBe listOf(day, LocalDate.of(2024, 1, 31))
    }

    @Test
    fun `INV-AC-22488 the key blocking in lower case throws`() {
        shouldThrow<IllegalArgumentException> { modeOf("blocking") }
    }

    @Test
    fun `INV-AC-22488 the key BLOCKING with a trailing space throws`() {
        shouldThrow<IllegalArgumentException> { modeOf("BLOCKING ") }
    }

    @Test
    fun `INV-AC-22488 the key BLOCK throws`() {
        shouldThrow<IllegalArgumentException> { modeOf("BLOCK") }
    }

    @Test
    fun `INV-AC-22488 no selected option throws`() {
        shouldThrow<IllegalStateException> { modeOf() }
    }

    @Test
    fun `INV-AC-22488 two selected options throw`() {
        shouldThrow<IllegalStateException> { modeOf("ADVISORY", "BLOCKING") }
    }

    @Test
    fun `INV-AC-22488 a resolver exception propagates`() {
        failure = { create(ElectionRequired::class.java, "x") }
        shouldThrow<ElectionRequired> { modeOf("ADVISORY") }
    }
}
