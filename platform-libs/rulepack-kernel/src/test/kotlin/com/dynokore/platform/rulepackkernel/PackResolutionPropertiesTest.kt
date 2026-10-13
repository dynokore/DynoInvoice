package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * STK-38 property test for INV-REQ-770's `resolve(pack_type, entity,
 * resolution_date)`: for any set of registered, non-conflicting immutable
 * pack versions and any resolution date, [InMemoryPackRegistry.resolve]
 * agrees with a naive reference implementation written independently of it
 * — the invariant this task's done-when calls "resolution by business date
 * over immutable versions".
 */
class PackResolutionPropertiesTest {
    private val packType = PackType("code-catalogue")
    private val tenantA = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000b"))
    private val epoch = LocalDate.of(2020, 1, 1)
    private val boundaryOffsets = Arb.list(Arb.long(0L..40L), 0..4)

    /** A non-overlapping, ordered timeline of versions for one (packType, tenant) lineage — realistic supersession. */
    private fun timeline(
        tenant: TenantId?,
        rawOffsets: List<Long>,
    ): List<PackVersion> {
        val boundaries = rawOffsets.distinct().sorted()
        return boundaries.mapIndexed { index, startOffset ->
            val from = epoch.plusDays(startOffset)
            val to = boundaries.getOrNull(index + 1)?.let { epoch.plusDays(it) }
            PackVersion(
                manifest =
                    PackManifest(
                        packId = PackId("fixture"),
                        packType = packType,
                        version = PackVersionLabel("v$index-${tenant?.toString() ?: "platform"}"),
                        effectiveFrom = from,
                        effectiveTo = to,
                        minEngineVersion = "1.0.0",
                        contentChecksums = emptyMap(),
                    ),
                tenant = tenant,
            )
        }
    }

    /** The independently-written reference [InMemoryPackRegistry.resolve] must agree with. */
    private fun naiveResolve(
        versions: List<PackVersion>,
        tenant: TenantId?,
        date: LocalDate,
    ): PackVersion? {
        val candidates =
            versions.filter {
                !it.effectiveFrom.isAfter(date) &&
                    (it.effectiveTo == null || date.isBefore(it.effectiveTo)) &&
                    (it.tenant == null || it.tenant == tenant)
            }
        val tenantSpecific = candidates.filter { it.tenant != null }
        val pool = if (tenantSpecific.isNotEmpty()) tenantSpecific else candidates.filter { it.tenant == null }
        return pool.maxByOrNull { it.effectiveFrom }
    }

    @Test
    fun `resolve agrees with an independent reference implementation over random timelines and dates`() =
        runTest {
            checkAll(
                boundaryOffsets,
                boundaryOffsets,
                Arb.long(0L..50L),
            ) { platformOffsets, tenantOffsets, dateOffset ->
                val registry = InMemoryPackRegistry()
                val platformVersions = timeline(null, platformOffsets)
                val tenantVersions = timeline(tenantA, tenantOffsets)
                (platformVersions + tenantVersions).forEach(registry::register)

                val resolutionDate = epoch.plusDays(dateOffset)
                val expected = naiveResolve(platformVersions + tenantVersions, tenantA, resolutionDate)
                val actual = registry.resolve(packType, tenantA, resolutionDate)
                assertEquals(expected, actual)

                // A different tenant with no override of its own only ever sees the platform pack, never tenantA's.
                val expectedForB = naiveResolve(platformVersions, tenantB, resolutionDate)
                assertEquals(expectedForB, registry.resolve(packType, tenantB, resolutionDate))
            }
        }

    @Test
    fun `whatever resolve returns is in force on the resolution date`() =
        runTest {
            checkAll(boundaryOffsets, Arb.long(0L..50L)) { offsets, dateOffset ->
                val registry = InMemoryPackRegistry()
                timeline(null, offsets).forEach(registry::register)
                val resolutionDate = epoch.plusDays(dateOffset)

                val resolved = registry.resolve(packType, null, resolutionDate)
                if (resolved != null) {
                    assert(!resolved.effectiveFrom.isAfter(resolutionDate)) {
                        "resolved ${resolved.version} whose effectiveFrom ${resolved.effectiveFrom} " +
                            "is after $resolutionDate"
                    }
                    assert(resolved.effectiveTo == null || resolutionDate.isBefore(resolved.effectiveTo)) {
                        "resolved ${resolved.version} whose effectiveTo ${resolved.effectiveTo} " +
                            "is not after $resolutionDate"
                    }
                }
            }
        }
}
