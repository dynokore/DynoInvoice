package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.util.UUID

/** INV-Q-8206: only a `Verified` outcome produces a pack to register, with its artefact and anchor label. */
class VerifiedPackTest {
    private val manifest =
        PackManifest(
            packId = PackId("code-catalogue-sg"),
            packType = PackType("code-catalogue"),
            version = PackVersionLabel("2026.10.1"),
            effectiveFrom = LocalDate.of(2026, 10, 1),
            effectiveTo = null,
            minEngineVersion = "1.0.0",
            contentChecksums = emptyMap(),
        )
    private val outcome = PackVerificationOutcome.Verified(manifest, "2026-train-primary")

    private fun artefact() =
        PackArtefact(
            manifest = "m".toByteArray(),
            checksums = "c".toByteArray(),
            signature = byteArrayOf(1, 2, 3),
            contents = byteArrayOf(4, 5, 6),
        )

    private fun pack(
        outcome: PackVerificationOutcome.Verified = this.outcome,
        artefact: PackArtefact = artefact(),
        tenant: TenantId? = null,
    ) = VerifiedPack.of(outcome, artefact, tenant)

    @Test
    fun `the factory sets version, artefact and label from its arguments`() {
        val tenant = TenantId(UUID.randomUUID())
        val artefact = artefact()
        val pack = pack(artefact = artefact, tenant = tenant)
        assertEquals(PackVersion(manifest, tenant), pack.version)
        assertEquals(artefact, pack.artefact)
        assertEquals("2026-train-primary", pack.trustedAnchorLabel)
        assertEquals(PackVersion(manifest, null), pack().version)
    }

    @Test
    fun `there is no public constructor and the factory takes a Verified outcome first`() {
        // kotlinc adds a public synthetic accessor (DefaultConstructorMarker) for the companion; not declared
        val declared = VerifiedPack::class.java.declaredConstructors.filter { !it.isSynthetic }
        assertEquals(1, declared.size)
        assertTrue(declared.none { Modifier.isPublic(it.modifiers) })
        // a value-class parameter mangles the method name
        val factories =
            (VerifiedPack::class.java.declaredMethods + VerifiedPack.Companion::class.java.declaredMethods)
                .filter { !it.isSynthetic && (it.name == "of" || it.name.startsWith("of-")) }
        assertTrue(factories.isNotEmpty())
        factories.forEach { assertEquals(PackVerificationOutcome.Verified::class.java, it.parameterTypes.first()) }
        assertFalse(
            PackVerificationOutcome.Verified::class.java.isAssignableFrom(PackVerificationOutcome.Rejected::class.java),
        )
    }

    @Test
    fun `packs built from equal parts are equal with equal hash codes`() {
        val a = pack()
        val b = pack()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `one byte of the signature changes equality`() {
        val changed = artefact().let { PackArtefact(it.manifest, it.checksums, byteArrayOf(1, 2, 9), it.contents) }
        assertNotEquals(pack(), pack(artefact = changed))
    }

    @Test
    fun `one byte of the contents changes equality`() {
        val changed = artefact().let { PackArtefact(it.manifest, it.checksums, it.signature, byteArrayOf(4, 5, 9)) }
        assertNotEquals(pack(), pack(artefact = changed))
    }

    @Test
    fun `the anchor label changes equality`() {
        assertNotEquals(pack(), pack(outcome = PackVerificationOutcome.Verified(manifest, "2026-train-successor")))
    }

    @Test
    fun `the tenant changes equality`() {
        assertNotEquals(pack(), pack(tenant = TenantId(UUID.randomUUID())))
    }

    @Test
    fun `toString holds no artefact bytes (CS-34)`() {
        val text = pack().toString()
        assertTrue(text.contains("signature=3B"))
        assertFalse(text.contains("[1, 2, 3]"))
        assertFalse(text.contains("[B@"))
    }
}
