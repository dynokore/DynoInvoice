package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class AuditPackWriterTest {
    private val tenant = TenantId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
    private val entity = PackageScope.EntityPeriod(tenant, EntityRef("entity-1"), PeriodRef("2026-Q3"))
    private val at = Instant.parse("2026-10-30T23:59:58.123Z")

    private fun onePerClass() =
        PackageContentClass.entries.mapIndexed {
            i,
            c,
            ->
            PackageItem(c, "item$i.dat", ByteArray(i + 3) { (it + i).toByte() })
        }

    private fun disk(
        dir: Path,
        files: PackageFiles,
    ): Path {
        files.paths.forEach { p ->
            val f = dir.resolve(p)
            Files.createDirectories(f.parent)
            Files.write(f, files.bytes(p)!!)
        }
        return dir
    }

    private fun sha(b: ByteArray) =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(b)
            .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    private fun lines(p: Path) = String(Files.readAllBytes(p), Charsets.UTF_8).removeSuffix("\n").split("\n")

    private fun info(root: Path) =
        lines(root.resolve("bag-info.txt")).associate {
            it.substringBefore(": ") to
                it.substringAfter(": ")
        }

    @Test
    fun `INV-AC-13401 one item of each class gives a valid bag`(
        @TempDir dir: Path,
    ) {
        val items = onePerClass()
        val root = disk(dir, AuditPackWriter.write(entity, items, at))
        listOf("bagit.txt", "bag-info.txt", "README.txt", "manifest-sha256.txt", "tagmanifest-sha256.txt").forEach {
            assertTrue(Files.isRegularFile(root.resolve(it)), it)
        }
        assertEquals(
            listOf("BagIt-Version: 1.0", "Tag-File-Character-Encoding: UTF-8"),
            lines(root.resolve("bagit.txt")),
        )
        val manifest = lines(root.resolve("manifest-sha256.txt"))
        assertEquals(6, manifest.size)
        assertEquals(manifest.sortedBy { it.substringAfter("  ") }, manifest)
        manifest.forEach { l ->
            val (digest, path) = l.split("  ", limit = 2)
            assertTrue(path.startsWith("data/"))
            assertEquals(sha(Files.readAllBytes(root.resolve(path))), digest)
        }
        val tags = lines(root.resolve("tagmanifest-sha256.txt"))
        assertEquals(
            listOf("bagit.txt", "bag-info.txt", "README.txt", "manifest-sha256.txt"),
            tags.map {
                it.substringAfter("  ")
            },
        )
        tags.forEach { l ->
            val (digest, path) = l.split("  ", limit = 2)
            assertEquals(sha(Files.readAllBytes(root.resolve(path))), digest)
        }
        val i = info(root)
        assertEquals("${items.sumOf { it.bytes().size }}.6", i["Payload-Oxum"])
        PackageContentClass.entries.forEach { assertEquals("1", i["Item-Count-${it.directory}"]) }
        assertEquals("2026-10-30T23:59:58.123Z", i["Generated-At"])
        assertEquals("2026-10-30", i["Bagging-Date"])
        val readme = String(Files.readAllBytes(root.resolve("README.txt")), Charsets.UTF_8)
        assertTrue(readme.contains("RFC 8493") && readme.contains("sha256sum -c manifest-sha256.txt"))
        PackageContentClass.entries.forEach { assertTrue(readme.contains(it.directory)) }
    }

    @Test
    fun `INV-AC-13401 scope decides kind and entity lines`(
        @TempDir a: Path,
        @TempDir b: Path,
    ) {
        val ep = info(disk(a, AuditPackWriter.write(entity, onePerClass(), at)))
        assertEquals("AUDIT_PACK", ep["Package-Kind"])
        assertEquals("entity-1", ep["Entity"])
        assertEquals("2026-Q3", ep["Period"])
        assertEquals(tenant.value.toString(), ep["Tenant"])
        val wt = info(disk(b, AuditPackWriter.write(PackageScope.WholeTenant(tenant), onePerClass(), at)))
        assertEquals("OFFBOARDING", wt["Package-Kind"])
        assertFalse(wt.containsKey("Entity") || wt.containsKey("Period"))
        assertEquals(tenant.value.toString(), wt["Tenant"])
    }

    @Test
    fun `INV-AC-13401 a class without item states 0`(
        @TempDir dir: Path,
    ) {
        val items =
            listOf(
                PackageItem(PackageContentClass.AUDIT_LOG, "a.log", byteArrayOf(1)),
                PackageItem(PackageContentClass.AUDIT_LOG, "b.log", byteArrayOf(2, 3)),
            )
        val i = info(disk(dir, AuditPackWriter.write(entity, items, at)))
        PackageContentClass.entries.forEach {
            assertEquals(if (it == PackageContentClass.AUDIT_LOG) "2" else "0", i["Item-Count-${it.directory}"])
        }
        assertEquals("3.2", i["Payload-Oxum"])
    }

    @Test
    fun `INV-AC-13401 zero items`(
        @TempDir dir: Path,
    ) {
        val files = AuditPackWriter.write(entity, emptyList(), at)
        val root = disk(dir, files)
        val i = info(root)
        assertEquals("0.0", i["Payload-Oxum"])
        PackageContentClass.entries.forEach { assertEquals("0", i["Item-Count-${it.directory}"]) }
        assertEquals(0, Files.size(root.resolve("manifest-sha256.txt")))
        assertEquals(5, files.paths.size)
    }

    @Test
    fun `INV-AC-13401 bytes that are not UTF-8 are kept`(
        @TempDir dir: Path,
    ) {
        val bad = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())
        val root =
            disk(
                dir,
                AuditPackWriter.write(
                    entity,
                    listOf(PackageItem(PackageContentClass.TRANSMITTED_XML, "x.xml", bad)),
                    at,
                ),
            )
        assertArrayEquals(bad, Files.readAllBytes(root.resolve("data/transmitted-xml/x.xml")))
        assertTrue(lines(root.resolve("manifest-sha256.txt")).single().startsWith(sha(bad) + "  "))
    }

    @Test
    fun `INV-AC-13401 same input gives same bytes in any item order`() {
        val items = onePerClass()
        val a = AuditPackWriter.write(entity, items, at)
        val b = AuditPackWriter.write(entity, items, at)
        val c = AuditPackWriter.write(entity, items.reversed(), at)
        assertEquals(a.paths, b.paths)
        assertEquals(a.paths, c.paths)
        a.paths.forEach {
            assertArrayEquals(a.bytes(it), b.bytes(it), it)
            assertArrayEquals(a.bytes(it), c.bytes(it), it)
        }
        assertEquals(a.paths.sorted(), a.paths)
    }

    @Test
    fun `INV-AC-13401 names with a slash, two dots or a percent sign are refused`() {
        listOf("a/b", "..", ".hidden", "a b", "x%25", "", "a\\b", "a".repeat(129)).forEach {
            assertThrows(IllegalArgumentException::class.java, {
                PackageItem(PackageContentClass.AUDIT_LOG, it, byteArrayOf())
            }, "'$it'")
        }
        PackageItem(PackageContentClass.AUDIT_LOG, "a".repeat(128), byteArrayOf())
    }

    @Test
    fun `duplicate class and name is refused and names the item`() {
        val items =
            listOf(
                PackageItem(PackageContentClass.AUDIT_LOG, "a.log", byteArrayOf(1)),
                PackageItem(PackageContentClass.AUDIT_LOG, "a.log", byteArrayOf(2)),
            )
        val e = assertThrows(IllegalArgumentException::class.java) { AuditPackWriter.write(entity, items, at) }
        assertTrue(e.message!!.contains("a.log"))
        AuditPackWriter.write(
            entity,
            listOf(items[0], PackageItem(PackageContentClass.TRANSMITTED_XML, "a.log", byteArrayOf())),
            at,
        )
    }

    @Test
    fun `a line feed in a bag-info value is refused`() {
        val s = PackageScope.EntityPeriod(tenant, EntityRef("a\nb"), PeriodRef("p"))
        assertThrows(IllegalArgumentException::class.java) { AuditPackWriter.write(s, emptyList(), at) }
        val r = PackageScope.EntityPeriod(tenant, EntityRef("a"), PeriodRef("p\rq"))
        assertThrows(IllegalArgumentException::class.java) { AuditPackWriter.write(r, emptyList(), at) }
    }

    @Test
    fun `the item holds its own copy of the bytes`() {
        val src = byteArrayOf(1, 2, 3)
        val item = PackageItem(PackageContentClass.AUDIT_LOG, "a.log", src)
        src[0] = 9
        val out = item.bytes()
        assertArrayEquals(byteArrayOf(1, 2, 3), out)
        out[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), item.bytes())
        val files = AuditPackWriter.write(entity, listOf(item), at)
        files.bytes("data/audit-log/a.log")!![0] = 7
        assertArrayEquals(byteArrayOf(1, 2, 3), files.bytes("data/audit-log/a.log"))
    }
}
