package com.dynokore.gst.consoleBff.application.profile

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ProfileApplierImplTest {
    private val vp01 =
        ProfileValue("VP-01", listOf("CUSTOMER_OIDC"), "PLATFORM_IDENTITY", "VP-01.DIRECTORY_CONNECTED")
    private val vp20 = ProfileValue("VP-20", listOf("DAILY_WINDOW"))
    private val vp01Ack = vp01.copy(needsAcknowledgement = listOf("CUSTOMER_OIDC"))

    private class Fixture(
        values: List<ProfileValue>,
        fact: Boolean? = true,
        factFor: (String) -> Boolean? = { fact },
        current: Map<String, String> = mapOf("VP-01" to "PLATFORM_IDENTITY"),
        failOn: String? = null,
    ) {
        val calls = mutableListOf<String>()
        val writes = mutableListOf<List<Any>>()
        val records = mutableListOf<List<Any>>()
        private val profile = ProfileDefinition("PRF-2", 1, ProfileStatus.AVAILABLE, values)
        private val applier =
            ProfileApplierImpl(
                { _, _, c -> factFor(c) },
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
            )
        val useCase =
            ApplyOperatingProfile(
                { _, _, _, _ -> true },
                { _, _ -> profile },
                { _, _, c -> factFor(c) },
                { _, _, vp -> current[vp] },
                applier,
            )

        fun result() = applier.apply(ProfileApplyCommand("t1", "u1", "ENT-1", ProfileScope.TENANT, profile))

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
    fun `an undecided second value is ConditionUndecided with nothing written even if the first changes`() {
        val vp02 = ProfileValue("VP-02", listOf("A"), "B", "VP-02.C")
        val f = Fixture(listOf(vp20, vp02), factFor = { if (it == "VP-02.C") null else true })
        f.apply() shouldBe ApplyOperatingProfileOutcome.ConditionUndecided("VP-02")
        f.calls shouldBe emptyList()
    }

    @Test
    fun `a failing first write lists no earlier write and stops`() {
        val f = Fixture(listOf(vp01, vp20), failOn = "VP-01")
        f.result().shouldBeInstanceOf<ProfileApplyResult.Failed>().written shouldBe emptyList()
        f.calls shouldBe listOf("write")
    }

    @Test
    fun `a failure at the second write lists exactly the first write and records nothing`() {
        val f = Fixture(listOf(vp01, vp20), failOn = "VP-20")
        f.result().shouldBeInstanceOf<ProfileApplyResult.Failed>().written shouldBe
            listOf(ProfileValueChange("VP-01", "PLATFORM_IDENTITY", "CUSTOMER_OIDC"))
        f.calls shouldBe listOf("write", "write")
        f.apply().shouldBeInstanceOf<ApplyOperatingProfileOutcome.Unavailable>()
    }

    @Test
    fun `a value whose option needs acknowledgement writes and records nothing`() {
        val f = Fixture(listOf(vp01Ack))
        f.apply() shouldBe ApplyOperatingProfileOutcome.AcknowledgementRequired(listOf("VP-01"))
        f.calls shouldBe emptyList()
    }

    @Test
    fun `an empty or look-alike acknowledgement list does not block the write`() {
        listOf(emptyList(), listOf("CUSTOMER_OIDC2")).forEach {
            val f = Fixture(listOf(vp01.copy(needsAcknowledgement = it)))
            f.apply().shouldBeInstanceOf<ApplyOperatingProfileOutcome.Applied>()
            f.calls shouldBe listOf("write", "record")
        }
    }

    @Test
    fun `the acknowledgement check uses the resolved otherwise option`() {
        val f = Fixture(listOf(vp01Ack), fact = false, current = mapOf("VP-01" to "X"))
        f.apply().shouldBeInstanceOf<ApplyOperatingProfileOutcome.Applied>()
        val g = Fixture(listOf(vp01.copy(needsAcknowledgement = listOf("PLATFORM_IDENTITY"))), fact = false)
        g.apply() shouldBe ApplyOperatingProfileOutcome.AcknowledgementRequired(listOf("VP-01"))
    }
}
