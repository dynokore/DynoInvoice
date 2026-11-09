package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** VP-52 accepts exactly the one catalogue option and refuses everything else; other keys go to the next port. */
class ElectionValueConstraintsTest {
    private val others =
        object : ValueConstraints {
            val seen = mutableListOf<VariationPointKey>()

            override fun check(
                target: SettingTarget,
                key: VariationPointKey,
                selected: List<SelectedOption>,
                onDate: LocalDate,
            ): ConstraintVerdict {
                seen += key
                return ConstraintVerdict.Refuse("others")
            }
        }
    private val constraints = ElectionValueConstraints(others)
    private val target = SettingTarget(null, ScopeRef(ScopeLevel.INSTALLATION, "installation"), null)
    private val today = LocalDate.of(2026, 11, 9)

    private fun vp52(vararg keys: String) =
        constraints.check(
            target,
            VariationPointKey("VP-52"),
            keys.map {
                SelectedOption(it)
            },
            today,
        )

    @Test
    fun `INV-AC-8923 VP-52 accepts CUSTOMER_KUBERNETES alone`() {
        vp52("CUSTOMER_KUBERNETES") shouldBe ConstraintVerdict.Accept
        others.seen shouldBe emptyList()
    }

    @Test
    fun `INV-AC-8923 VP-52 refuses the deferred appliance, look-alikes, an empty selection and two options`() {
        for (
        selection in
        listOf(
            arrayOf("SUPPLIED_APPLIANCE"),
            arrayOf("customer_kubernetes"),
            arrayOf("CUSTOMER_KUBERNETES2"),
            arrayOf(),
            arrayOf("CUSTOMER_KUBERNETES", "SUPPLIED_APPLIANCE"),
        )
        ) {
            vp52(*selection).shouldBeInstanceOf<ConstraintVerdict.Refuse>()
        }
    }

    @Test
    fun `INV-AC-8923 VP-51 and VP-64 still go to the next port`() {
        for (key in listOf("VP-51", "VP-64")) {
            val verdict = constraints.check(target, VariationPointKey(key), listOf(SelectedOption("X")), today)
            verdict shouldBe ConstraintVerdict.Refuse("others")
        }
        others.seen shouldBe listOf(VariationPointKey("VP-51"), VariationPointKey("VP-64"))
    }
}
