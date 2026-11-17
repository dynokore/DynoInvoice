package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.build.ConnectionFamilyLookup
import com.dynokore.gst.payload.application.build.FamilyRequest
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-AC-23664: the family of the connection on the document date, inclusive; no connection or date fails closed. */
class ConnectionFamilyLookupTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val election = LocalDate.of(2026, 3, 20)

    /** VP-71 as the policy store answers it: SG_PEPPOL_BIS elected for CONN-A from [election] on, else PINT_SG. */
    private class Elections(
        private val connection: String,
        private val from: LocalDate,
    ) : PolicyReader {
        val calls = mutableListOf<Triple<VariationPointKey, PolicyScope, LocalDate>>()

        override fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): ResolvedSetting {
            calls.add(Triple(key, scope, at))
            val elected = scope.ref(ScopeLevel.AP_CONNECTION)?.id == connection && !at.isBefore(from)
            return ResolvedSetting(
                key,
                at,
                listOf(SelectedOption(if (elected) "SG_PEPPOL_BIS" else "PINT_SG")),
                PolicySource.CatalogueDefault,
            )
        }
    }

    private fun lookup(policy: PolicyReader) = ConnectionFamilyLookup(policy) { tenant }

    @Test
    fun `INV-AC-23664 the election effective on the date applies on the date`() {
        val policy = Elections("CONN-A", election)
        lookup(policy).optionKeyOf(FamilyRequest("E-1", "CONN-A", election)) shouldBe "SG_PEPPOL_BIS"
        val (key, scope, at) = policy.calls.single()
        key shouldBe VariationPointKey("VP-71")
        at shouldBe election
        scope.tenant shouldBe tenant
        scope.entity shouldBe "E-1"
        scope.ref(ScopeLevel.AP_CONNECTION)?.id shouldBe "CONN-A"
    }

    @Test
    fun `INV-AC-23664 the day before the election resolves PINT_SG`() {
        val policy = Elections("CONN-A", election)
        lookup(policy).optionKeyOf(FamilyRequest("E-1", "CONN-A", election.minusDays(1))) shouldBe "PINT_SG"
    }

    @Test
    fun `INV-AC-23664 another connection is not affected`() {
        val policy = Elections("CONN-A", election)
        lookup(policy).optionKeyOf(FamilyRequest("E-1", "CONN-B", election)) shouldBe "PINT_SG"
    }

    @Test
    fun `INV-AC-23664 a null connectionRef fails closed`() {
        val policy = Elections("CONN-A", election)
        val failure =
            shouldThrow<IllegalStateException> { lookup(policy).optionKeyOf(FamilyRequest("E-9", null, election)) }
        failure.message shouldContain "E-9"
        policy.calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-23664 a null date fails closed`() {
        val policy = Elections("CONN-A", election)
        val failure =
            shouldThrow<IllegalStateException> { lookup(policy).optionKeyOf(FamilyRequest("E-9", "CONN-A", null)) }
        failure.message shouldContain "E-9"
        policy.calls.size shouldBe 0
    }
}
