package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Only the two session adapters may name the platform binding (INV-T-14458 session store ruling (d), owner answer of
 * 2026-11-11): the whole relative paths of the `.kt` files of `src/main` of four modules that contain
 * `PlatformTenantBinding`, `bindPlatform` or `TenantId.PLATFORM` equal the exact set of two.
 */
class PlatformBindingScopeTest {
    private val modules =
        listOf("console-bff-adapters", "console-bff-bootstrap", "console-bff-application", "console-bff-domain")
    private val expected =
        setOf(
            "console-bff-adapters/src/main/kotlin/com/dynokore/gst/consoleBff/adapters/session/JooqSessionStore.kt",
            "console-bff-adapters/src/main/kotlin/com/dynokore/gst/consoleBff/adapters/session/SessionSweeper.kt",
        )

    private fun repoRoot(): Path {
        var dir: Path = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) dir = checkNotNull(dir.parent) { "no repository root" }
        return dir
    }

    /** The relative paths of the `.kt` files under `<module>/src/main` of [root] that name the binding. */
    private fun naming(root: Path): Set<String> =
        modules
            .map { root.resolve(it).resolve("src/main") }
            .filter { Files.isDirectory(it) }
            .flatMap { main ->
                Files.walk(main).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
                }
            }.filter { file ->
                val text = Files.readString(file)
                TOKENS.any { text.contains(it) }
            }.map { root.relativize(it).joinToString("/") }
            .toSet()

    /** A copy of the main sources of the four modules, so a probe never touches the working tree. */
    private fun copy(into: Path): Path {
        val root = repoRoot()
        modules.forEach { module ->
            val main = root.resolve(module).resolve("src/main")
            if (!Files.isDirectory(main)) return@forEach
            Files.walk(main).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.forEach { f ->
                    val target = into.resolve(root.relativize(f).toString())
                    Files.createDirectories(target.parent)
                    Files.copy(f, target)
                }
            }
        }
        return into
    }

    private fun add(
        root: Path,
        relative: String,
        text: String,
    ) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    @Test
    fun `INV-AC-14457 exactly the store and the sweeper name the platform binding`() {
        naming(repoRoot()) shouldBe expected
    }

    @Test
    fun `INV-AC-14457 a third class naming the binding is found`(
        @TempDir dir: Path,
    ) {
        val root = copy(dir)
        add(root, "console-bff-adapters/src/main/kotlin/x/Third.kt", "import a.PlatformTenantBinding\nclass Third")
        naming(root) shouldBe expected + "console-bff-adapters/src/main/kotlin/x/Third.kt"
    }

    @Test
    fun `INV-AC-14457 the same class name in another package is found`(
        @TempDir dir: Path,
    ) {
        val root = copy(dir)
        add(root, "console-bff-bootstrap/src/main/kotlin/other/JooqSessionStore.kt", "val x = TenantId.PLATFORM")
        naming(root) shouldBe expected + "console-bff-bootstrap/src/main/kotlin/other/JooqSessionStore.kt"
    }

    @Test
    fun `INV-AC-14457 bindPlatform in a filter class is found`(
        @TempDir dir: Path,
    ) {
        val root = copy(dir)
        add(root, "console-bff-adapters/src/main/kotlin/x/Filter.kt", "fun f() { binding.bindPlatform(\"a\") { } }")
        naming(root) shouldBe expected + "console-bff-adapters/src/main/kotlin/x/Filter.kt"
    }

    @Test
    fun `INV-AC-14457 a renamed copy of the store is found`(
        @TempDir dir: Path,
    ) {
        val root = copy(dir)
        val store = "console-bff-adapters/src/main/kotlin/com/dynokore/gst/consoleBff/adapters/session/JooqSessionStore"
        add(root, "${store}2.kt", Files.readString(root.resolve("$store.kt")))
        naming(root) shouldBe expected + "${store}2.kt"
    }

    @Test
    fun `INV-AC-14457 the word in a markdown file only is not a finding`(
        @TempDir dir: Path,
    ) {
        val root = copy(dir)
        add(root, "console-bff-adapters/src/main/kotlin/x/NOTES.md", "PlatformTenantBindingNote")
        naming(root) shouldBe expected
    }

    private companion object {
        val TOKENS = listOf("PlatformTenantBinding", "bindPlatform", "TenantId.PLATFORM")
    }
}
