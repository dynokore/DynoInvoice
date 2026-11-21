package com.dynokore.gst.consoleBff.application.profile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ApplyOperatingProfileTest {
    private fun planned(
        ref: String,
        version: Int,
        vararg vps: String,
    ) = ProfileDefinition(ref, version, ProfileStatus.PLANNED, vps.map { ProfileValue(it, listOf("OPT_$it")) })

    private val vp01 =
        ProfileValue("VP-01", listOf("CUSTOMER_OIDC"), "PLATFORM_IDENTITY", "VP-01.DIRECTORY_CONNECTED")
    private val prf2 = ProfileDefinition("PRF-2", 1, ProfileStatus.AVAILABLE, listOf(vp01))

    private class Fixture(
        profiles: List<ProfileDefinition>,
        grant: Boolean = true,
        fact: Boolean? = true,
        currentValue: String? = "PLATFORM_IDENTITY",
    ) {
        val applied = mutableListOf<ProfileApplyCommand>()
        val reads = mutableListOf<String>()
        val useCase =
            ApplyOperatingProfile(
                { _, _, _, _ -> grant },
                { ref, v ->
                    reads += "find"
                    profiles.firstOrNull { it.profileRef == ref && it.version == v }
                },
                { _, _, _ ->
                    reads += "fact"
                    fact
                },
                { _, _, _ -> currentValue },
                {
                    applied += it
                    ProfileApplyResult.Applied(it.changes)
                },
            )

        fun run(
            mode: ProfileMode,
            ref: String,
            version: Int,
        ) = useCase.execute("t1", "u1", "ENT-1", ApplyOperatingProfileRequest(mode, ProfileScope.TENANT, ref, version))
    }

    @Test
    fun `preview of an available profile lists only the values that change`() {
        val change = ProfileValueChange("VP-01", "PLATFORM_IDENTITY", "CUSTOMER_OIDC")
        val yes = Fixture(listOf(prf2), fact = true)
        yes.run(ProfileMode.PREVIEW, "PRF-2", 1) shouldBe
            ApplyOperatingProfileOutcome.Previewed("PRF-2", 1, ProfileStatus.AVAILABLE, listOf(change))
        val no = Fixture(listOf(prf2), fact = false)
        no.run(ProfileMode.PREVIEW, "PRF-2", 1) shouldBe
            ApplyOperatingProfileOutcome.Previewed("PRF-2", 1, ProfileStatus.AVAILABLE, emptyList())
        val unknown = Fixture(listOf(prf2), fact = null)
        unknown.run(ProfileMode.PREVIEW, "PRF-2", 1) shouldBe ApplyOperatingProfileOutcome.ConditionUndecided("VP-01")
        listOf(yes, no, unknown).forEach { it.applied.size shouldBe 0 }
    }

    private fun plannedCase(
        profile: ProfileDefinition,
        vps: List<String>,
    ) {
        val f = Fixture(listOf(profile), currentValue = null)
        val preview =
            f.run(ProfileMode.PREVIEW, profile.profileRef, profile.version)
                as ApplyOperatingProfileOutcome.Previewed
        preview.status shouldBe ProfileStatus.PLANNED
        preview.changes.map { it.variationPoint } shouldBe vps
        f.run(ProfileMode.APPLY, profile.profileRef, profile.version) shouldBe
            ApplyOperatingProfileOutcome.NotAvailable(ProfileStatus.PLANNED)
        f.applied.size shouldBe 0
    }

    @Test
    fun `INV-AC-2239 the group profile is previewed as planned with its five values and applying it is refused`() {
        val vps = listOf("VP-01", "VP-02", "VP-15", "VP-28", "VP-33")
        plannedCase(planned("PRF-3", 1, *vps.toTypedArray()), vps)
    }

    @Test
    fun `INV-AC-2240 the enterprise profile is previewed as planned with its four values and applying it is refused`() {
        val vps = listOf("VP-14", "VP-15", "VP-32", "VP-46")
        plannedCase(planned("PRF-4", 1, *vps.toTypedArray()), vps)
    }

    @Test
    fun `INV-AC-2243 the regulated private profile is previewed as planned with seven values and apply is refused`() {
        val vps = listOf("VP-01", "VP-02", "VP-05", "VP-08", "VP-32", "VP-43", "VP-44")
        plannedCase(planned("PRF-7", 3, *vps.toTypedArray()), vps)
    }

    @Test
    fun `permission is checked before the catalogue and port failures fail closed`() {
        val denied = Fixture(emptyList(), grant = false)
        denied.run(ProfileMode.PREVIEW, "NOPE", 1) shouldBe ApplyOperatingProfileOutcome.Forbidden
        denied.reads.size shouldBe 0
        Fixture(emptyList()).run(ProfileMode.PREVIEW, "NOPE", 1) shouldBe ApplyOperatingProfileOutcome.NotFound
        Fixture(emptyList()).run(ProfileMode.PREVIEW, " ", 1) shouldBe
            ApplyOperatingProfileOutcome.Invalid("profileRef is blank")
        Fixture(emptyList()).run(ProfileMode.PREVIEW, "PRF-2", 0) shouldBe
            ApplyOperatingProfileOutcome.Invalid("profileVersion is below 1")
        val boom =
            ApplyOperatingProfile(
                { _, _, _, _ -> true },
                { _, _ -> error("down") },
                { _, _, _ -> true },
                { _, _, _ -> null },
                { ProfileApplyResult.Failed("x") },
            )
        val request = ApplyOperatingProfileRequest(ProfileMode.PREVIEW, ProfileScope.TENANT, "PRF-3", 1)
        boom.execute("t", "u", "e", request) shouldBe
            ApplyOperatingProfileOutcome.Unavailable("port failure: IllegalStateException")
    }

    @Test
    fun `apply of an available profile calls the applier once`() {
        val f = Fixture(listOf(prf2))
        f.run(ProfileMode.APPLY, "PRF-2", 1) shouldBe
            ApplyOperatingProfileOutcome.Applied(
                "PRF-2",
                1,
                listOf(ProfileValueChange("VP-01", "PLATFORM_IDENTITY", "CUSTOMER_OIDC")),
            )
        f.applied.size shouldBe 1
    }
}
