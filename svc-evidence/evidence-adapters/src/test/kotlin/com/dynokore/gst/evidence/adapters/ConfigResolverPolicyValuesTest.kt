package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ConfigResolverPolicyValues
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-AC-11708: the pack period, the tenant period and the notice lead come from VP-63 through the policy reader. */
class ConfigResolverPolicyValuesTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val on = LocalDate.parse("2026-03-01")

    private fun values(selected: List<SelectedOption>): ConfigResolverPolicyValues {
        val reader =
            object : PolicyReader {
                override fun resolve(
                    key: VariationPointKey,
                    scope: PolicyScope,
                    at: LocalDate,
                ): ResolvedSetting {
                    key shouldBe VariationPointKey("VP-63")
                    return ResolvedSetting(key, at, selected, PolicySource.CatalogueDefault, emptyList())
                }
            }
        return ConfigResolverPolicyValues(reader)
    }

    private fun shorter(
        days: Int,
        lead: Int?,
    ) = SelectedOption(
        "SHORTER",
        "{\"responseDays\":$days" + lead?.let { ",\"noticeLeadDays\":$it" }.orEmpty() + "}",
    )

    @Test
    fun `INV-AC-11708 no tenant value gives the pack period 30, no tenant period and the lead 7`() {
        val v = values(listOf(SelectedOption("PACK_DEFAULT_30D", null)))
        v.packResponseDays() shouldBe 30
        v.tenantResponseDays(tenant, on) shouldBe null
        v.noticeLeadDays(tenant, on) shouldBe 7
    }

    @Test
    fun `INV-AC-11708 a tenant period of 14 gives 14 and the lead stays 7`() {
        val v = values(listOf(shorter(14, null)))
        v.tenantResponseDays(tenant, on) shouldBe 14
        v.noticeLeadDays(tenant, on) shouldBe 7
        v.packResponseDays() shouldBe 30
    }

    @Test
    fun `INV-AC-11708 a tenant lead of 14 gives 14`() {
        val v = values(listOf(shorter(21, 14)))
        v.noticeLeadDays(tenant, on) shouldBe 14
        v.tenantResponseDays(tenant, on) shouldBe 21
    }
}
