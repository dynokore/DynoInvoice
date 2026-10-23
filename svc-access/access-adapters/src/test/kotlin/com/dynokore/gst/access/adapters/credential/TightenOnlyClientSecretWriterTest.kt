package com.dynokore.gst.access.adapters.credential

import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.function.Supplier

/**
 * The VP-77 tighten-only rule (INV-REQ-2760, INV-Q-8985), tested on the writer itself with a counting delegate and a
 * reader whose stored value is a fixed date, so no clock is involved: REGULATED_PRIVATE is stored from [stored] on.
 */
class TightenOnlyClientSecretWriterTest {
    private val vp77 = VariationPointKey("VP-77")
    private val vp78 = VariationPointKey("VP-78")
    private val stored = LocalDate.of(2030, 6, 15)

    private class CountingWriter : PolicyWriter {
        var calls = 0

        override fun setPolicy(command: SetPolicyCommand): PolicyChange {
            calls++
            throw RecordedCall
        }

        override fun acknowledgeControlRelaxation(
            command: com.dynokore.platform.configresolver.AcknowledgeRelaxationCommand,
        ) = error("not used")

        override fun validate(
            key: VariationPointKey,
            scope: PolicyScope,
            level: ScopeLevel,
            selected: List<SelectedOption>,
            onDate: LocalDate,
        ) = error("not used")
    }

    /** The delegate was reached: [CountingWriter.setPolicy] has no real change to return. */
    private object RecordedCall : RuntimeException()

    private class StoredReader(
        private val regulatedFrom: LocalDate?,
    ) : PolicyReader {
        val asked = mutableListOf<LocalDate>()

        override fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): ResolvedSetting {
            asked += at
            val regulated = regulatedFrom != null && !at.isBefore(regulatedFrom)
            return ResolvedSetting(
                key,
                at,
                listOf(SelectedOption(if (regulated) "REGULATED_PRIVATE" else "SAAS_STANDARD", "{}")),
                PolicySource.CatalogueDefault,
            )
        }
    }

    private fun command(
        key: VariationPointKey,
        option: String,
        from: LocalDate,
    ) = SetPolicyCommand(
        key,
        PolicyScope.installation(),
        ScopeLevel.INSTALLATION,
        listOf(SelectedOption(option, "{}")),
        from,
        "preset",
        Actor("op", setOf("deployment.operator")),
        CorrelationId("corr-1"),
        CausationId("cause-1"),
    )

    private fun reachesDelegate(
        writer: CountingWriter,
        guarded: TightenOnlyClientSecretWriter,
        command: SetPolicyCommand,
    ) {
        assertThatThrownBy { guarded.setPolicy(command) }.isSameAs(RecordedCall)
        assertThat(writer.calls).isEqualTo(1)
    }

    @Test
    fun `SAAS_STANDARD is refused with REGULATED_PRIVATE stored for today, a past date and a future date`() {
        listOf(stored, stored.minusDays(10), stored.plusDays(10)).forEach { from ->
            val delegate = CountingWriter()
            val reader = StoredReader(stored)
            val guarded = TightenOnlyClientSecretWriter(delegate) { reader }
            assertThatThrownBy { guarded.setPolicy(command(vp77, "SAAS_STANDARD", from)) }
                .describedAs("effective $from")
                .isInstanceOfSatisfying(PolicyRefused::class.java) {
                    assertThat(it.refusal).isEqualTo(PolicyRefusal.LOOSER_AT_INSTALLATION)
                }
            assertThat(delegate.calls).describedAs("delegate calls for $from").isZero()
            if (from.isBefore(stored)) {
                assertThat(
                    reader.asked,
                ).describedAs("a past date sees the later value").contains(LocalDate.of(9999, 12, 31))
            }
        }
    }

    @Test
    fun `REGULATED_PRIVATE again reaches the delegate once`() {
        val delegate = CountingWriter()
        val guarded = TightenOnlyClientSecretWriter(delegate) { StoredReader(stored) }
        reachesDelegate(delegate, guarded, command(vp77, "REGULATED_PRIVATE", stored.plusDays(1)))
    }

    @Test
    fun `SAAS_STANDARD with SAAS_STANDARD stored reaches the delegate once`() {
        val delegate = CountingWriter()
        val guarded = TightenOnlyClientSecretWriter(delegate) { StoredReader(null) }
        reachesDelegate(delegate, guarded, command(vp77, "SAAS_STANDARD", stored))
    }

    @Test
    fun `SAAS_STANDARD with nothing stored reaches the delegate once`() {
        val delegate = CountingWriter()
        val guarded = TightenOnlyClientSecretWriter(delegate) { StoredReader(null) }
        reachesDelegate(delegate, guarded, command(vp77, "SAAS_STANDARD", stored.plusDays(3)))
    }

    @Test
    fun `the default option of VP-78 passes with REGULATED_PRIVATE stored for VP-77`() {
        val delegate = CountingWriter()
        val reader = StoredReader(stored)
        val guarded = TightenOnlyClientSecretWriter(delegate) { reader }
        reachesDelegate(delegate, guarded, command(vp78, "SAAS_STANDARD", stored))
        assertThat(reader.asked).describedAs("another variation point is never looked up").isEmpty()
    }

    @Test
    fun `a reader that throws makes setPolicy throw and the delegate is not called`() {
        val delegate = CountingWriter()
        val failure = IllegalStateException("reader down")
        val guarded = TightenOnlyClientSecretWriter(delegate, Supplier<PolicyReader> { throw failure })
        assertThatThrownBy { guarded.setPolicy(command(vp77, "SAAS_STANDARD", stored)) }.isSameAs(failure)
        assertThat(delegate.calls).isZero()
    }
}
