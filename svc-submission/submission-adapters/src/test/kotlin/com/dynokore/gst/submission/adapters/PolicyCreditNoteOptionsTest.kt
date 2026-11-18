package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.adapters.policy.PolicyCreditNoteOptions
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The VP-59 option reader against a fake of the resolver (INV-AC-24516, INV-REQ-709). */
class PolicyCreditNoteOptionsTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val day = LocalDate.of(2026, 3, 10)
    private val calls = mutableListOf<Triple<String, PolicyScope, LocalDate>>()

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

    private fun setting(
        key: String,
        at: LocalDate,
        vararg options: String,
    ): ResolvedSetting {
        // ResolvedSetting refuses an empty selection at construction, so the no-option answer (case 3) is made by
        // emptying the list it holds afterwards.
        val selected = (if (options.isEmpty()) listOf("X") else options.toList()).map { SelectedOption(it, null) }
        val held = selected.toMutableList()
        val setting =
            create(ResolvedSetting::class.java, key, at, held, PolicySource.CatalogueDefault, emptyList<Any>())
        if (options.isEmpty()) held.clear()
        return setting
    }

    private class ResolverDown : RuntimeException("resolver down")

    private fun adapter(answer: (String, LocalDate) -> ResolvedSetting) =
        PolicyCreditNoteOptions(
            object : PolicyReader {
                override fun resolve(
                    key: VariationPointKey,
                    scope: PolicyScope,
                    at: LocalDate,
                ): ResolvedSetting {
                    calls += Triple(key.value, scope, at)
                    return answer(key.value, at)
                }
            },
        )

    private fun answering(vararg options: String) = adapter { k, d -> setting(k, d, *options) }

    @Test
    fun `INV-AC-24516 case 1 one selected option RELEASE_ALONE returns RELEASE_ALONE`() {
        answering("RELEASE_ALONE").at(tenant, "E-001", day) shouldBe CreditNoteOption.RELEASE_ALONE
    }

    @Test
    fun `INV-AC-24516 case 2 one selected option HOLD_FOR_APPROVER returns HOLD_FOR_APPROVER`() {
        answering("HOLD_FOR_APPROVER").at(tenant, "E-001", day) shouldBe CreditNoteOption.HOLD_FOR_APPROVER
    }

    @Test
    fun `INV-AC-24516 case 3 no selected option throws IllegalStateException and returns no default`() {
        shouldThrow<IllegalStateException> { answering().at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-24516 case 4 two selected options throw IllegalStateException`() {
        shouldThrow<IllegalStateException> { answering("RELEASE_ALONE", "HOLD_FOR_APPROVER").at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-24516 case 5 a lower case key throws IllegalStateException`() {
        shouldThrow<IllegalStateException> { answering("release_alone").at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-24516 case 6 an unknown key throws IllegalStateException`() {
        shouldThrow<IllegalStateException> { answering("OTHER").at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-24516 case 7 a resolver exception propagates`() {
        val boom = ResolverDown()
        val thrown = shouldThrow<ResolverDown> { adapter { _, _ -> throw boom }.at(tenant, "E-001", day) }
        (thrown === boom) shouldBe true
    }

    @Test
    fun `INV-AC-24516 the key, scope and date passed are VP-59, the entity scope of the tenant and the given date`() {
        answering("RELEASE_ALONE").at(tenant, "E-001", day)
        calls.size shouldBe 1
        calls[0].first shouldBe VariationPoint.VP_59.id
        calls[0].second.toString() shouldBe PolicyScope.entity(tenant, entity = "E-001").toString()
        calls[0].second.entity shouldBe "E-001"
        calls[0].third shouldBe day
        PolicyCreditNoteOptions.KEY shouldBe VariationPoint.VP_59.id
    }
}
