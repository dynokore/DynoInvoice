package com.dynokore.gst.payload.application.version

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.override.VALIDATION_OVERRIDE
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class GetLatestDocumentVersionTest {
    private val d = UUID.fromString("00000000-0000-0000-0000-00000000000d")
    private val x = UUID.fromString("00000000-0000-0000-0000-00000000000e")

    /** Holds versions in insertion order, so a first-wins use case is caught; the port answers the maximum. */
    private class FakePort(
        private val stored: Map<UUID, List<Pair<Int, String>>>,
    ) : LatestDocumentVersions {
        override fun latest(docUuid: UUID): LatestVersionFacts? =
            stored[docUuid]?.maxByOrNull { it.first }?.let { LatestVersionFacts(it.first, it.second) }
    }

    private class RecordingAuthority(
        private val holds: Boolean,
    ) : OverrideAuthority {
        val asked = mutableListOf<Triple<String, String, String>>()

        override fun holds(
            userId: String,
            permission: String,
            entityRef: String,
        ): Boolean {
            asked += Triple(userId, permission, entityRef)
            return holds
        }
    }

    private fun port(vararg versions: Pair<Int, String>) = FakePort(mapOf(d to versions.toList()))

    @Test
    fun `INV-AC-21134 found answers the maximum version 3 of 1, 2 and 3 and asks the authority once`() {
        val authority = RecordingAuthority(true)
        val result = GetLatestDocumentVersion(port(1 to "E0", 2 to "E0", 3 to "E1"), authority).execute("u-1", d)
        result shouldBe LatestVersionResult.Found(d, 3)
        authority.asked shouldBe listOf(Triple("u-1", VALIDATION_OVERRIDE, "E1"))
    }

    @Test
    fun `INV-AC-21134 found answers 5 of versions 1 and 5`() {
        val authority = RecordingAuthority(true)
        val result = GetLatestDocumentVersion(port(5 to "E1", 1 to "E0"), authority).execute("u-2", d)
        result shouldBe LatestVersionResult.Found(d, 5)
        authority.asked shouldBe listOf(Triple("u-2", "validation.override", "E1"))
    }

    @Test
    fun `INV-AC-21134 unknown document is NotFound and the authority is never asked`() {
        val authority = RecordingAuthority(true)
        val result = GetLatestDocumentVersion(port(1 to "E1"), authority).execute("u-1", x)
        result shouldBe LatestVersionResult.NotFound
        authority.asked shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21134 denied caller is Forbidden and no version is exposed`() {
        val authority = RecordingAuthority(false)
        val result = GetLatestDocumentVersion(port(1 to "E1", 2 to "E1", 3 to "E1"), authority).execute("u-1", d)
        result shouldBe LatestVersionResult.Forbidden
        (result is LatestVersionResult.Found) shouldBe false
        authority.asked shouldBe listOf(Triple("u-1", VALIDATION_OVERRIDE, "E1"))
    }
}
