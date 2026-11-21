package com.dynokore.gst.consoleBff.application.profile

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ProfileApplierImplTest {
    private val vp01 =
        ProfileValue("VP-01", listOf("CUSTOMER_OIDC"), "PLATFORM_IDENTITY", "VP-01.DIRECTORY_CONNECTED")
    private val vp20 = ProfileValue("VP-20", listOf("DAILY_WINDOW"))

    private class Fixture(
        values: List<ProfileValue>,
        fact: Boolean? = true,
        current: Map<String, String> = mapOf("VP-01" to "PLATFORM_IDENTITY"),
        failOn: String? = null,
    ) {
        val calls = mutableListOf<String>()
        val writes = mutableListOf<List<Any>>()
        val records = mutableListOf<List<Any>>()
        private val profile = ProfileDefinition("PRF-2", 1, ProfileStatus.AVAILABLE, values)
        val useCase =
            ApplyOperatingProfile(
                { _, _, _, _ -> true },
                { _, _ -> profile },
                { _, _, _ -> fact },
                { _, _, vp -> current[vp] },
                ProfileApplierImpl(
                    { _, _, _ -> fact },
                    { _, _, vp -> current[vp] },
                    { _, _, _, _, vp, option, ref, v ->
                        calls += "write"
                        writes += listOf(vp, option, ref, v)
                        if (vp == failOn) PolicyWriteResult.Rejected("down") else PolicyWriteResult.Success
                    },
                    { _, scope, _, ref, v ->
                        calls += "record"
                        records += listOf(scope, ref, v)
                        PolicyWriteResult.Success
                    },
                ),
            )

        fun apply() =
            useCase.execute(
                "t1",
                "u1",
                "ENT-1",
                ApplyOperatingProfileRequest(ProfileMode.APPLY, ProfileScope.TENANT, "PRF-2", 1),
            )
    }

    @Test
    fun `INV-AC-2238 applying the in-house profile writes only the identity source and records the profile`() {
        val f = Fixture(listOf(vp01))
        f.apply() shouldBe
            ApplyOperatingProfileOutcome.Applied(
                "PRF-2",
                1,
                listOf(ProfileValueChange("VP-01", "PLATFORM_IDENTITY", "CUSTOMER_OIDC")),
            )
        f.writes shouldBe listOf(listOf("VP-01", "CUSTOMER_OIDC", "PRF-2", 1))
        f.records shouldBe listOf(listOf(ProfileScope.TENANT, "PRF-2", 1))
        f.calls shouldBe listOf("write", "record")
    }

    @Test
    fun `a false condition equal to the current value writes nothing and still records once`() {
        val f = Fixture(listOf(vp01), fact = false)
        f.apply() shouldBe ApplyOperatingProfileOutcome.Applied("PRF-2", 1, emptyList())
        f.calls shouldBe listOf("record")
    }

    @Test
    fun `an undecided condition writes and records nothing`() {
        val f = Fixture(listOf(vp01), fact = null)
        f.apply().shouldBeInstanceOf<ApplyOperatingProfileOutcome.Unavailable>()
        f.calls shouldBe emptyList()
    }

    @Test
    fun `a failing first write stops the later write and the record`() {
        val f = Fixture(listOf(vp01, vp20), failOn = "VP-01")
        f.apply().shouldBeInstanceOf<ApplyOperatingProfileOutcome.Unavailable>()
        f.calls shouldBe listOf("write")
    }

    @Test
    fun `a failure at the second write lists the first write and records nothing`() {
        val f = Fixture(listOf(vp01, vp20), failOn = "VP-20")
        val out = f.apply() as ApplyOperatingProfileOutcome.Unavailable
        out.reason shouldBe "write failed at VP-20: down; written: VP-01"
        f.calls shouldBe listOf("write", "write")
    }
}
