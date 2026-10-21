package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.util.UUID

/** INV-Q-8206: `register(VerifiedPack)` on the port, and its default against `register(PackVersion)`. */
class VerifiedPackRegistryTest {
    private val packType = PackType("mapping")
    private val tenant = TenantId(UUID.randomUUID())

    private fun manifest(
        version: String,
        from: LocalDate,
        to: LocalDate? = null,
    ) = PackManifest(
        packId = PackId("mapping-sg"),
        packType = packType,
        version = PackVersionLabel(version),
        effectiveFrom = from,
        effectiveTo = to,
        minEngineVersion = "1.0.0",
        contentChecksums = emptyMap(),
    )

    private fun verified(
        manifest: PackManifest,
        tenant: TenantId? = null,
    ) = VerifiedPack.of(
        PackVerificationOutcome.Verified(manifest, "2026-train-primary"),
        PackArtefact(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4)),
        tenant,
    )

    private val d = { m: Int, day: Int -> LocalDate.of(2026, m, day) }
    private val timeline =
        listOf(
            manifest("1", d(1, 1), d(6, 1)) to null,
            manifest("2", d(6, 1)) to null,
            manifest("t1", d(3, 1), d(9, 1)) to tenant,
        )

    @Test
    fun `the port's method set is exactly the three registered shapes`() {
        val methods =
            PackRegistry::class.java.methods
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name to it.parameterTypes.toList() }
                .toSet()
        assertTrue(methods.contains("register" to listOf(PackVersion::class.java)))
        assertTrue(methods.contains("register" to listOf(VerifiedPack::class.java)))
        val others = methods.filter { it.first != "register" }
        assertEquals(1, others.size)
        assertTrue(others.single().first.startsWith("resolve")) // value-class parameter names may be mangled
        assertEquals(LocalDate::class.java, others.single().second.last())
        assertEquals(3, others.single().second.size)
        assertEquals(3, methods.size)
    }

    @Test
    fun `resolution through register(VerifiedPack) equals resolution through register(PackVersion)`() {
        val viaPack = InMemoryPackRegistry()
        val viaVersion = InMemoryPackRegistry()
        timeline.forEach { (m, t) ->
            viaPack.register(verified(m, t))
            viaVersion.register(PackVersion(m, t))
        }
        val dates =
            listOf(
                d(1, 1).minusDays(1),
                d(1, 1),
                d(3, 1),
                d(6, 1),
                d(9, 1),
                d(5, 31),
                d(8, 31),
                d(12, 31),
                d(12, 31).plusYears(1),
            )
        for (who in listOf(null, tenant, TenantId(UUID.randomUUID()))) {
            for (date in dates) {
                assertEquals(
                    viaVersion.resolve(packType, who, date),
                    viaPack.resolve(packType, who, date),
                    "$who $date",
                )
            }
        }
    }

    @Test
    fun `a conflicting re-registration throws and an identical one is a no-op`() {
        val registry = InMemoryPackRegistry()
        val m = manifest("1", d(1, 1))
        registry.register(verified(m))
        registry.register(verified(m))
        assertEquals(PackVersion(m), registry.resolve(packType, null, d(2, 1)))
        assertThrows(IllegalArgumentException::class.java) { registry.register(verified(manifest("1", d(2, 1)))) }
    }

    @Test
    fun `a double overriding only register(VerifiedPack) receives the pack with its artefact`() {
        val received = mutableListOf<VerifiedPack>()
        val double =
            object : PackRegistry {
                override fun register(version: PackVersion) = throw AssertionError("must not be called")

                override fun register(pack: VerifiedPack) {
                    received += pack
                }

                override fun resolve(
                    packType: PackType,
                    tenant: TenantId?,
                    resolutionDate: LocalDate,
                ): PackVersion? = null
            }
        val pack = verified(manifest("1", d(1, 1)))
        double.register(pack)
        assertSame(pack, received.single())
        assertEquals(pack.artefact, received.single().artefact)
    }
}
