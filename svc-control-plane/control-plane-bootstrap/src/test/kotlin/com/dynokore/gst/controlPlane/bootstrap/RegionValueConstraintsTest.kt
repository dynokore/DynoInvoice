package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.commercial.RegionList
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The value check of VP-45 and its deny default (INV-T-9238, INV-AC-2224). */
class RegionValueConstraintsTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val target = SettingTarget(tenant, ScopeRef(ScopeLevel.TENANT, tenant.toString()), null)
    private val onList: RegionList = listOnly("SG")
    private val check = RegionValueConstraints(onList)

    private fun verdict(
        key: String,
        value: String?,
    ) = check.check(
        target,
        VariationPointKey(key),
        listOf(SelectedOption("region", value)),
        LocalDate.of(2026, 11, 8),
    )

    @Test
    fun `INV-AC-2224 the key of another catalogue row is refused and nothing else decides it`() {
        verdict("VP-72", "\"SG\"").shouldBeInstanceOf<ConstraintVerdict.Refuse>()
    }

    @Test
    fun `INV-AC-2224 VP-45 accepts a listed key and refuses an absent or a longer key`() {
        verdict("VP-45", "\"SG\"") shouldBe ConstraintVerdict.Accept
        verdict("VP-45", "\"ZZ\"").shouldBeInstanceOf<ConstraintVerdict.Refuse>()
        verdict("VP-45", "\"SGX\"").shouldBeInstanceOf<ConstraintVerdict.Refuse>()
        verdict("VP-45", null).shouldBeInstanceOf<ConstraintVerdict.Refuse>()
    }

    private fun listOnly(vararg keys: String): RegionList =
        object : RegionList {
            override fun contains(key: RegionKey): Boolean = key.value in keys

            override fun statement(key: RegionKey): String? = null
        }
}
