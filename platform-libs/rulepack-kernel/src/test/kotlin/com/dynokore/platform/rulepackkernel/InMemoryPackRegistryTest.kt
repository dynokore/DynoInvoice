package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-REQ-770's own `resolve(pack_type, entity, resolution_date)` behaviour, over immutable versions. */
class InMemoryPackRegistryTest {
    private val packType = PackType("code-catalogue")

    private fun manifest(
        version: String,
        effectiveFrom: LocalDate,
        effectiveTo: LocalDate? = null,
    ) = PackManifest(
        packId = PackId("code-catalogue-sg"),
        packType = packType,
        version = PackVersionLabel(version),
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        minEngineVersion = "1.0.0",
        contentChecksums = emptyMap(),
    )

    @Test
    fun `resolve returns null when nothing is registered`() {
        val registry = InMemoryPackRegistry()
        assertNull(registry.resolve(packType, null, LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `resolve returns null before the earliest version's effective date`() {
        val registry = InMemoryPackRegistry()
        registry.register(PackVersion(manifest("1", LocalDate.of(2026, 1, 1))))

        assertNull(registry.resolve(packType, null, LocalDate.of(2025, 12, 31)))
    }

    @Test
    fun `resolve picks the version in force on the resolution date, over an open-ended effective_to`() {
        val registry = InMemoryPackRegistry()
        val v1 = PackVersion(manifest("1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1)))
        val v2 = PackVersion(manifest("2", LocalDate.of(2026, 6, 1)))
        registry.register(v1)
        registry.register(v2)

        assertEquals(v1, registry.resolve(packType, null, LocalDate.of(2026, 3, 1)))
        assertEquals(v2, registry.resolve(packType, null, LocalDate.of(2026, 6, 1))) // effective_from inclusive
        assertEquals(v1, registry.resolve(packType, null, LocalDate.of(2026, 5, 31))) // effective_to exclusive
    }

    @Test
    fun `a backfilled document resolves against a superseded (retained) version (INV-REQ-1089)`() {
        val registry = InMemoryPackRegistry()
        val v1 = PackVersion(manifest("1", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)))
        val v2 = PackVersion(manifest("2", LocalDate.of(2026, 1, 1)))
        registry.register(v1)
        registry.register(v2)

        assertEquals(v1, registry.resolve(packType, null, LocalDate.of(2021, 6, 15)))
    }

    @Test
    fun `a tenant-specific version takes precedence over the platform version for the same date (INV-REQ-770)`() {
        val registry = InMemoryPackRegistry()
        val tenant = TenantId(UUID.randomUUID())
        val platform = PackVersion(manifest("platform-1", LocalDate.of(2026, 1, 1)))
        val override = PackVersion(manifest("tenant-1", LocalDate.of(2026, 1, 1)), tenant = tenant)
        registry.register(platform)
        registry.register(override)

        assertEquals(override, registry.resolve(packType, tenant, LocalDate.of(2026, 6, 1)))
        assertEquals(platform, registry.resolve(packType, null, LocalDate.of(2026, 6, 1)))
        assertEquals(platform, registry.resolve(packType, TenantId(UUID.randomUUID()), LocalDate.of(2026, 6, 1)))
    }

    @Test
    fun `re-registering the same version with the identical manifest is a no-op (at-least-once redelivery)`() {
        val registry = InMemoryPackRegistry()
        val version = PackVersion(manifest("1", LocalDate.of(2026, 1, 1)))
        registry.register(version)
        registry.register(version) // RulePackStaged delivered twice

        assertEquals(version, registry.resolve(packType, null, LocalDate.of(2026, 6, 1)))
    }

    @Test
    fun `re-registering the same lineage with a different manifest is refused — versions are immutable`() {
        val registry = InMemoryPackRegistry()
        registry.register(PackVersion(manifest("1", LocalDate.of(2026, 1, 1))))

        assertThrows(IllegalArgumentException::class.java) {
            // same version label, different date
            registry.register(PackVersion(manifest("1", LocalDate.of(2026, 2, 1))))
        }
    }
}
