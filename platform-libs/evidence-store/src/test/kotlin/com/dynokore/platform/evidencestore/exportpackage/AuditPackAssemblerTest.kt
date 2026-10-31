package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.DIGEST_MISMATCH
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuditPackAssemblerTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
    private val e1 = EntityRef("E1")
    private val e2 = EntityRef("E2")
    private val p1 = PeriodRef("P1")
    private val p2 = PeriodRef("P2")
    private val at = Instant.parse("2026-10-31T00:00:00Z")

    private fun cand(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        cls: PackageContentClass,
        name: String,
    ) = PackageCandidate(tenant, entity, period, PackageItem(cls, name, "$name-bytes".toByteArray()))

    private fun sixOf(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        tag: String,
    ) = PackageContentClass.entries.map { cand(tenant, entity, period, it, "$tag-${it.directory}.dat") }

    private fun source(files: PackageFiles) =
        object : PackageSource {
            override fun paths(): Set<String> = files.paths.toSet()

            override fun read(path: String): ByteArray = files.bytes(path)!!
        }

    private fun assembled(result: AssemblyResult): AssemblyResult.Assembled {
        assertTrue(result is AssemblyResult.Assembled) { "expected Assembled but was $result" }
        return result as AssemblyResult.Assembled
    }

    private fun dataPaths(files: PackageFiles) = files.paths.filter { it.startsWith("data/") }

    private fun scope() = PackageScope.EntityPeriod(t1, e1, p1)

    private fun mixed() = sixOf(t1, e1, p1, "a") + sixOf(t1, e1, p2, "b").take(1) + sixOf(t1, e2, p1, "c").take(1)

    @Test
    fun `INV-AC-13403 entity and period scope includes exactly the six and excludes the others`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed(), at))
        assertEquals(6, r.included)
        assertEquals(2, r.excluded)
        val expected = PackageContentClass.entries.map { "data/${it.directory}/a-${it.directory}.dat" }.sorted()
        assertEquals(expected, dataPaths(r.files))
    }

    @Test
    fun `INV-AC-13403 a foreign tenant look-alike refuses the whole assembly`() {
        val foreign = cand(t2, e1, p1, PackageContentClass.AUDIT_LOG, "foreign.dat")
        val r = AuditPackAssembler.assemble(scope(), mixed() + foreign, at)
        assertEquals(AssemblyResult.Refused(AssemblyRefusal.FOREIGN_TENANT, "foreign.dat"), r)
    }

    @Test
    fun `INV-AC-13403 a foreign tenant item of another entity is refused, not dropped`() {
        val foreign = cand(t2, e2, p2, PackageContentClass.AUDIT_LOG, "foreign.dat")
        val r = AuditPackAssembler.assemble(scope(), mixed() + foreign, at)
        assertEquals(AssemblyResult.Refused(AssemblyRefusal.FOREIGN_TENANT, "foreign.dat"), r)
    }

    @Test
    fun `INV-AC-13403 a period differing only in case is excluded`() {
        val lower = cand(t1, e1, PeriodRef("p1"), PackageContentClass.AUDIT_LOG, "lower.dat")
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed() + lower, at))
        assertEquals(6, r.included)
        assertEquals(3, r.excluded)
        assertTrue(dataPaths(r.files).none { it.endsWith("lower.dat") })
    }

    @Test
    fun `INV-AC-13404 whole tenant package holds every candidate, is offboarding and verifies`() {
        val all = sixOf(t1, e1, p1, "a") + sixOf(t1, e1, p2, "b") + sixOf(t1, e2, p1, "c") + sixOf(t1, e2, p2, "d")
        val r = assembled(AuditPackAssembler.assemble(PackageScope.WholeTenant(t1), all, at))
        assertEquals(24, r.included)
        assertEquals(0, r.excluded)
        val bagInfo = String(r.files.bytes("bag-info.txt")!!)
        assertTrue("Package-Kind: OFFBOARDING" in bagInfo.lines())
        assertTrue(bagInfo.lines().none { it.startsWith("Entity:") || it.startsWith("Period:") })
        val v = PackageVerifier.verify(source(r.files))
        assertTrue(v is PackageVerification.Valid) { "expected Valid but was $v" }
        val counts = (v as PackageVerification.Valid).itemCounts
        PackageContentClass.entries.forEach {
            assertEquals(
                all.count { c ->
                    c.item.contentClass == it
                },
                counts[it.directory],
            )
        }

        val flipped =
            r.files.paths
                .associateWith { r.files.bytes(it)!! }
                .toMutableMap()
        val target = dataPaths(r.files).first()
        flipped[target]!![0] = (flipped.getValue(target)[0].toInt() xor 1).toByte()
        val bad =
            PackageVerifier.verify(
                object : PackageSource {
                    override fun paths(): Set<String> = flipped.keys

                    override fun read(path: String): ByteArray = flipped.getValue(path)
                },
            )
        assertTrue(bad is PackageVerification.Invalid) { "expected Invalid but was $bad" }
        assertTrue((bad as PackageVerification.Invalid).problems.any { it.code == DIGEST_MISMATCH })
    }

    @Test
    fun `INV-AC-13404 the entity period result verifies as valid`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed(), at))
        val v = PackageVerifier.verify(source(r.files))
        assertTrue(v is PackageVerification.Valid) { "expected Valid but was $v" }
        assertEquals(6, (v as PackageVerification.Valid).payloadFiles)
    }

    @Test
    fun `a duplicate class and name is refused`() {
        val dup = sixOf(t1, e1, p1, "a") + cand(t1, e1, p1, PackageContentClass.AUDIT_LOG, "a-audit-log.dat")
        val r = AuditPackAssembler.assemble(scope(), dup, at)
        assertEquals(AssemblyResult.Refused(AssemblyRefusal.DUPLICATE_ITEM, "a-audit-log.dat"), r)
    }

    @Test
    fun `an empty candidate list gives a valid empty package`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), emptyList(), at))
        assertEquals(0, r.included)
        assertEquals(0, r.excluded)
        assertTrue(PackageVerifier.verify(source(r.files)) is PackageVerification.Valid)
    }
}
