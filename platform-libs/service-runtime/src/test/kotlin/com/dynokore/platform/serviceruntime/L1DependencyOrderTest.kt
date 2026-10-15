package com.dynokore.platform.serviceruntime

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The layer rules of platform-libs (STK-60, ARC-16; INV-T-4715, INV-T-4747; coordinator rulings INV-Q-4724, INV-Q-4732,
 * INV-Q-4734, INV-Q-4758, INV-Q-4760) on this repository. The bytecode boundary rules do not apply to `gst.library`, so
 * this test is the only layer gate in platform-libs. It enforces, for every module that declares `gstLibrary.layer`:
 *
 *  1. Imports, in every source set (Kotlin through Konsist, Java by a scan): L1 modules import only the L1 modules the
 *     [MODEL] allows (the INV-MOD "Order within L1" lines, transitive closure; crypto usable by every L1 module); L2
 * imports only L1; L3 imports L1 and L2. A module with no source files does
 * not fail and is checked once it has one.
 *  2. Package ownership: every Kotlin and Java file of a layered module declares a package under
 * `com.dynokore.platform.<module name without hyphens>`, except the exact
 * [OWNERSHIP_EXCEPTIONS] (path and package).
 *  3. Completeness: a module that declares a layer is in [MODEL] with the same layer, and a module included in
 *     settings.gradle.kts declares a layer unless its name is exactly one of [UNLAYERED].
 *
 * `the real modules keep the L1 order` checks the real repository (CI's
 * unit-test job runs it on every pipeline, and its
 * negative test plants a forbidden import and requires this case to fail). The other cases are the rules' self-test,
 * against small fixture repositories ([Repo]).
 */
class L1DependencyOrderTest {
    @Test
    fun `the real modules keep the L1 order`() {
        val findings = Rules.findings(realRoot(), Konsist.scopeFromProject())
        // The exceptions applied are reported, so a reviewer sees exactly what the waiver covers.
        println("package-ownership exceptions applied: ${OWNERSHIP_EXCEPTIONS.map { it.path }}")
        if (findings.isNotEmpty()) fail<Unit>(findings.joinToString("\n", prefix = "layer rules:\n"))
    }

    // ---- imports: passing ----

    @Test
    fun `passes a fixture that uses only what Keel allows`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        // INV-MOD "Order within L1": audit-log uses outbox-and-queue, which uses tenancy-kernel; the rule enforces the
        // transitive closure, so audit-log may import tenancy-kernel too. service-runtime is the composition root.
        repo.importing("audit-log", "outbox-and-queue", "A")
        repo.importing("audit-log", "tenancy-kernel", "B")
        repo.importing("config-resolver", "audit-log", "C")
        repo.importing("evidence-store", "tenancy-kernel", "E")
        repo.importing("evidence-store", "crypto", "E2")
        repo.importing("rulepack-kernel", "tenancy-kernel", "R")
        repo.importing("rulepack-kernel", "tenancy-jooq", "R2", sourceSet = "test")
        repo.importing("tenancy-jooq", "tenancy-kernel", "J")
        repo.importing("service-runtime", "adapter-runtime", "S")
        repo.importing("service-runtime", "config-resolver", "T")
        repo.importing("product-runtime", "service-runtime", "P1")
        repo.importing("product-runtime", "config-resolver", "P2")
        repo.importing("product-runtime", "outbox-and-queue", "P3")
        repo.importing("product-runtime", "audit-log", "P4")
        // the same four in implTest: the rule does not differ by source set
        listOf("service-runtime", "config-resolver", "outbox-and-queue", "audit-log").forEachIndexed { i, to ->
            repo.importing("product-runtime", to, "PT$i", sourceSet = "implTest")
        }
        repo.importing("party-registry", "tenancy-kernel", "L2")

        assertDoesNotThrow { assertClean(repo) }
    }

    @Test
    fun `passes an L2 module with no files, and platform-libs-bom and contract-tests with no declaration`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir, withoutFiles = setOf("party-registry", "integration-canon"))
        repo.importing("evidence-store", "tenancy-kernel", "E")

        assertDoesNotThrow { assertClean(repo) }
        assertTrue(File(dir, "party-registry/src").exists().not())
        assertTrue(File(dir, "platform-libs-bom/build.gradle.kts").readText().contains("layer").not())
    }

    @Test
    fun `passes the two allowlisted tenancy-jooq files with package org-jooq-impl`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        OWNERSHIP_EXCEPTIONS.forEach { assertTrue(File(dir, it.path).readText().contains("package ${it.packageName}")) }

        assertDoesNotThrow { assertClean(repo) }
    }

    // ---- imports: failing ----

    @Test
    fun `fails when a module that uses nothing imports another`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("tenancy-kernel", "audit-log", "T")

        fails(repo, "'tenancy-kernel' layer does not depends on 'audit-log' layer")
    }

    @Test
    fun `fails when a module imports a sibling outside its allowed uses`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        // evidence-store uses only tenancy-kernel (and tenancy-jooq, crypto), even
        // though a linear order would allow this.
        repo.importing("evidence-store", "outbox-and-queue", "E")

        fails(repo, "'evidence-store' layer does not depends on 'outbox-and-queue' layer")
    }

    @Test
    fun `fails when another L1 module depends on service-runtime`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("audit-log", "service-runtime", "A")

        fails(repo, "'audit-log' layer does not depends on 'service-runtime' layer")
    }

    @Test
    fun `fails when crypto imports tenancy-kernel`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("crypto", "tenancy-kernel", "C")

        fails(repo, "'crypto' layer does not depends on 'tenancy-kernel' layer")
    }

    @Test
    fun `fails when tenancy-kernel imports tenancy-jooq`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("tenancy-kernel", "tenancy-jooq", "T")

        fails(repo, "'tenancy-kernel' layer does not depends on 'tenancy-jooq' layer")
    }

    @Test
    fun `fails when adapter-runtime imports tenancy-jooq`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("adapter-runtime", "tenancy-jooq", "A")

        fails(repo, "'adapter-runtime' layer does not depends on 'tenancy-jooq' layer")
    }

    @Test
    fun `fails when an L1 module imports a product-runtime type, in any source set`(
        @TempDir dir: File,
    ) {
        listOf("main", "test", "implTest").forEach { sourceSet ->
            val repo = writeL1ModuleStubs(File(dir, sourceSet))
            repo.importing("service-runtime", "product-runtime", "P", sourceSet = sourceSet)

            fails(repo, "'service-runtime' layer does not depends on 'product-runtime' layer")
        }
    }

    @Test
    fun `fails when an L1 module imports an L2 type`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("evidence-store", "party-registry", "E")

        fails(repo, "'evidence-store' layer does not depends on 'party-registry' layer")
    }

    @Test
    fun `fails when an L2 module imports another L2 module`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("party-registry", "document-lifecycle", "P")

        fails(repo, "'party-registry' layer does not depends on 'document-lifecycle' layer")
    }

    @Test
    fun `fails when an L2 module imports product-runtime`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.importing("calendar-and-periods", "product-runtime", "C")

        fails(repo, "'calendar-and-periods' layer does not depends on 'product-runtime' layer")
    }

    @Test
    fun `fails an upward import of a module that has no files yet`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir, withoutFiles = setOf("party-registry"))
        repo.importing("evidence-store", "party-registry", "E")

        fails(repo, "'evidence-store' layer does not depends on 'party-registry' layer")
    }

    @Test
    fun `fails a Java import that breaks the layer rules`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/test/java/com/dynokore/platform/crypto/J.java",
            "package com.dynokore.platform.crypto;\nimport com.dynokore.platform.tenancykernel.Stub;\nclass J {}\n",
        )

        fails(repo, "'crypto' layer does not depends on 'tenancy-kernel' layer")
    }

    @Test
    fun `fails the second Java import on one line`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/test/java/com/dynokore/platform/crypto/J.java",
            "package com.dynokore.platform.crypto;\n" +
                "import java.util.List; import com.dynokore.platform.tenancykernel.Stub;\nclass J {}\n",
        )

        fails(repo, "'crypto' layer does not depends on 'tenancy-kernel' layer")
    }

    @Test
    fun `an import inside a comment or a string is not an import`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/test/java/com/dynokore/platform/crypto/J.java",
            "package com.dynokore.platform.crypto;\n// import com.dynokore.platform.tenancykernel.A;\n" +
                "/* import com.dynokore.platform.tenancykernel.B; */\n" +
                "class J { String s = \"import com.dynokore.platform.tenancykernel.C;\"; }\n",
        )

        assertClean(repo)
    }

    @Test
    fun `fails a package line inside a block comment that hides the real package`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/main/java/com/dynokore/platform/crypto/J.java",
            "/*\npackage com.dynokore.platform.crypto;\n*/\npackage org.evil;\nclass J {}\n",
        )
        repo.file(
            "crypto",
            "src/main/kotlin/com/dynokore/platform/crypto/K.kt",
            "/* package com.dynokore.platform.crypto */\npackage org.evil\nclass K\n",
        )
        // Kotlin block comments nest: the compiler reads the last package line as the declaration
        repo.file(
            "crypto",
            "src/main/kotlin/com/dynokore/platform/crypto/N.kt",
            "/* /* */\npackage com.dynokore.platform.crypto\n*/\npackage org.evil\nclass N\n",
        )

        val findings = Rules.findings(repo.dir, Konsist.scopeFromExternalDirectory(repo.dir.absolutePath))
        val evil = "package org.evil is not under com.dynokore.platform.crypto"
        assertTrue(findings.count { evil in it } == 3) { "$findings" }
    }

    @Test
    fun `fails a file whose only package line is in a comment`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/main/java/com/dynokore/platform/crypto/J.java",
            "// package com.dynokore.platform.crypto;\nclass J {}\n",
        )

        fails(repo, "declares no package")
    }

    // ---- package ownership ----

    @Test
    fun `fails when a service-runtime file declares package productruntime`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "service-runtime",
            "src/implTest/kotlin/com/dynokore/platform/productruntime/ProductRuntimeConfiguration.kt",
            "package com.dynokore.platform.productruntime\nclass ProductRuntimeConfiguration\n",
        )

        fails(repo, "package com.dynokore.platform.productruntime is not under com.dynokore.platform.serviceruntime")
    }

    @Test
    fun `fails when a service-runtime file declares package db-migrationrunner`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "service-runtime",
            "src/implTest/kotlin/db/migrationrunner/V1__Jobs.kt",
            "package db.migrationrunner\nclass V1__Jobs\n",
        )

        fails(repo, "package db.migrationrunner is not under com.dynokore.platform.serviceruntime")
    }

    @Test
    fun `fails a file with no package declaration`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file("crypto", "src/main/kotlin/NoPackage.kt", "class NoPackage\n")

        fails(repo, "declares no package")
    }

    @Test
    fun `fails any other org-jooq package, or a third file in the allowlisted directory`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "tenancy-jooq",
            "src/springTest/java/org/jooq/impl/SpoofThird.java",
            "package org.jooq.impl;\nclass SpoofThird {}\n",
        )
        fails(
            repo,
            "tenancy-jooq/src/springTest/java/org/jooq/impl/SpoofThird.java: package org.jooq.impl is not under",
        )

        val other = writeL1ModuleStubs(File(dir, "other"))
        other.file("evidence-store", "src/main/java/org/jooq/Other.java", "package org.jooq;\nclass Other {}\n")
        fails(other, "evidence-store/src/main/java/org/jooq/Other.java: package org.jooq is not under")
    }

    @Test
    fun `fails when an allowlisted path declares another package`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "tenancy-jooq",
            "src/springTest/java/org/jooq/impl/SpoofField.java",
            "package org.jooq.other;\nclass SpoofField {}\n",
        )

        fails(
            repo,
            "tenancy-jooq/src/springTest/java/org/jooq/impl/SpoofField.java: package org.jooq.other is not under",
        )
    }

    @Test
    fun `fails when an allowlisted path exists in another module`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "rulepack-kernel",
            "src/springTest/java/org/jooq/impl/SpoofField.java",
            "package org.jooq.impl;\nclass SpoofField {}\n",
        )

        fails(
            repo,
            "rulepack-kernel/src/springTest/java/org/jooq/impl/SpoofField.java: package org.jooq.impl is not under",
        )
    }

    @Test
    fun `fails when an allowlist entry's file is absent`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        File(dir, "tenancy-jooq/src/springTest/java/org/jooq/impl/SpoofWindow.java").delete()

        fails(repo, "allowlisted file tenancy-jooq/src/springTest/java/org/jooq/impl/SpoofWindow.java is absent")
    }

    @Test
    fun `a waived file stays subject to the import rules`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "tenancy-jooq",
            "src/springTest/java/org/jooq/impl/SpoofField.java",
            "package org.jooq.impl;\nimport com.dynokore.platform.auditlog.Stub;\nclass SpoofField {}\n",
        )

        fails(repo, "'tenancy-jooq' layer does not depends on 'audit-log' layer")
    }

    // ---- completeness ----

    @Test
    fun `fails when a module declaring Layer-L1 is missing from the map`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.module("new-module", "L1")

        fails(repo, "'new-module' declares gstLibrary.layer but is not in the model map")
    }

    @Test
    fun `fails when a declared layer differs from the map`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.module("crypto", "L2")

        fails(repo, "'crypto' declares layer L2 but the model map says L1")
    }

    @Test
    fun `fails when a new included module declares no layer and is not on the allowlist`(
        @TempDir dir: File,
    ) {
        listOf(
            "new-module",
            "product-runtime-x-client",
            "product-runtime-tenancy-client2",
            "Contract-Tests",
        ).forEach { name ->
            val repo = writeL1ModuleStubs(File(dir, name))
            repo.module(name, layer = null)

            fails(repo, "'$name' is included in settings.gradle.kts but declares no gstLibrary.layer")
        }
    }

    @Test
    fun `reads a layer from the generated layer-properties when the build file has none`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.module("new-module", layer = null)
        repo.file("new-module", "build/generated/gst-layer/gst/layer.properties", "module=new-module\nlayer=L1\n")

        fails(repo, "'new-module' declares gstLibrary.layer but is not in the model map")
    }

    @Test
    fun `the unlayered allowlist is exactly the eight names`() {
        assertTrue(
            UNLAYERED ==
                setOf(
                    "platform-libs-bom",
                    "contract-tests",
                    "product-runtime-tenancy-client",
                    "product-runtime-access-client",
                    "product-runtime-structure-events",
                    "product-runtime-profile-events",
                    "product-runtime-authority-events",
                    "product-runtime-policy-events",
                ),
        )
    }

    // ---- helpers ----

    private fun realRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: error("settings.gradle.kts not found above ${File("").absoluteFile}")
    }
}

/** A fixture repository: settings.gradle.kts, a build file per layered module, and sources. */
internal class Repo(
    val dir: File,
) {
    private val plain = linkedSetOf<String>()
    private val pairs = linkedMapOf<String, String>()

    fun module(
        name: String,
        layer: String?,
        pairDir: String? = null,
    ) {
        if (pairDir != null) pairs[name] = pairDir else plain += name
        val moduleDir = if (pairDir != null) File(dir, "product-runtime/contracts/$pairDir") else File(dir, name)
        moduleDir.mkdirs()
        File(moduleDir, "build.gradle.kts").writeText(
            if (layer == null) {
                "plugins { id(\"base\") }\n"
            } else {
                "import gst.build.Layer\nplugins { id(\"gst.library\") }\ngstLibrary {\n    layer = Layer.$layer\n}\n"
            },
        )
        File(dir, "settings.gradle.kts").writeText(
            "include(\n" + plain.joinToString("") { "    \"$it\",\n" } + ")\n" +
                "listOf(\n" + pairs.entries.joinToString("") { "    \"${it.key}\" to \"${it.value}\",\n" } +
                ").forEach { (name, d) -> include(name) }\n",
        )
    }

    fun file(
        module: String,
        relativePath: String,
        content: String,
    ) {
        val file = File(File(dir, module), relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    fun importing(
        from: String,
        to: String,
        name: String,
        sourceSet: String = "main",
    ) {
        val f = from.replace("-", "")
        val t = to.replace("-", "")
        file(
            from,
            "src/$sourceSet/kotlin/com/dynokore/platform/$f/$name.kt",
            "package com.dynokore.platform.$f\nimport com.dynokore.platform.$t.Stub\nclass $name(val s: Stub)\n",
        )
    }
}

internal enum class Level { L1, L2, L3 }

/**
 * One module of the model map. [uses] are its direct L1 uses (INV-MOD "Order within L1"); [usesAllL1]
 * is the composition root.
 */
internal class Entry(
    val name: String,
    val level: Level,
    val key: String,
    val uses: Set<String> = emptySet(),
    val usesAllL1: Boolean = false,
)

internal val PERSISTENCE = setOf("tenancy-kernel", "tenancy-jooq")

/** The model map: INV-MOD "Order within L1" as amended by INV-Q-4732, ARC-16 for L2 and L3 (INV-Q-4734). */
internal val MODEL =
    listOf(
        Entry("service-runtime", Level.L1, "INV-MOD-2371", usesAllL1 = true),
        Entry("outbox-and-queue", Level.L1, "INV-MOD-2372", PERSISTENCE),
        Entry("rulepack-kernel", Level.L1, "INV-MOD-2373", PERSISTENCE),
        Entry("adapter-runtime", Level.L1, "INV-MOD-2374"),
        Entry("tenancy-kernel", Level.L1, "INV-MOD-2737"),
        Entry("evidence-store", Level.L1, "INV-MOD-2738", PERSISTENCE),
        Entry("audit-log", Level.L1, "INV-MOD-2739", setOf("outbox-and-queue", "tenancy-jooq")),
        Entry("config-resolver", Level.L1, "INV-MOD-2402", setOf("audit-log", "outbox-and-queue", "tenancy-jooq")),
        Entry("crypto", Level.L1, "INV-MOD-4745"),
        Entry("tenancy-jooq", Level.L1, "INV-MOD-4746", setOf("tenancy-kernel")),
        Entry("party-registry", Level.L2, "INV-MOD-2740"),
        Entry("document-lifecycle", Level.L2, "INV-MOD-2741"),
        Entry("calendar-and-periods", Level.L2, "INV-MOD-2742"),
        Entry("reconciliation-engine", Level.L2, "INV-MOD-2743"),
        Entry("integration-canon", Level.L2, "INV-MOD-2744"),
        Entry("product-runtime", Level.L3, "INV-MOD-2745"),
    )

/**
 * Modules included in settings.gradle.kts that declare no layer; equality only, never a glob or
 * prefix (INV-Q-4734).
 */
internal val UNLAYERED =
    setOf(
        "platform-libs-bom", // the BOM: constraints only
        "contract-tests", // test-only, publishes nothing
        // generated gst.contract-client and gst.event-types projects folded into product-runtime's artefact
        "product-runtime-tenancy-client",
        "product-runtime-access-client",
        "product-runtime-structure-events",
        "product-runtime-profile-events",
        "product-runtime-authority-events",
        "product-runtime-policy-events",
    )

internal class OwnershipException(
    val path: String,
    val packageName: String,
    val why: String,
)

/**
 * Exact (repo-relative path, package) pairs that waive package ownership only (INV-Q-4760); the
 * import rules still apply.
 */
internal val OWNERSHIP_EXCEPTIONS =
    listOf(
        OwnershipException(
            "tenancy-jooq/src/springTest/java/org/jooq/impl/SpoofField.java",
            "org.jooq.impl",
            "FatJarTest fixture: the tenant guard must refuse a custom part " +
                "declared in jOOQ's own package; the package is the attack",
        ),
        OwnershipException(
            "tenancy-jooq/src/springTest/java/org/jooq/impl/SpoofWindow.java",
            "org.jooq.impl",
            "FatJarTest fixture: extends package-private " +
                "AbstractWindowFunction; no public-API route reproduces the attack",
        ),
    )

internal object Rules {
    const val ROOT = "com.dynokore.platform"

    private val byName = MODEL.associateBy { it.name }
    private val bySegment = MODEL.associateBy { it.name.replace("-", "") }
    private val l1 = MODEL.filter { it.level == Level.L1 }.map { it.name }

    /** Modules [m]'s files may import: the transitive closure of its direct uses, plus crypto for every L1 module. */
    fun allowed(m: Entry): Set<String> =
        when (m.level) {
            Level.L1 -> if (m.usesAllL1) l1.toSet() else closure(m) + "crypto"
            Level.L2 -> l1.toSet()
            Level.L3 -> MODEL.filter { it.level != Level.L3 }.map { it.name }.toSet()
        } + m.name

    private fun closure(m: Entry): Set<String> {
        val out = linkedSetOf<String>()
        val todo = ArrayDeque(m.uses)
        while (todo.isNotEmpty()) {
            val next = todo.removeFirst()
            if (out.add(next)) todo += byName.getValue(next).uses
        }
        return out
    }

    private fun moduleOfPackage(pkg: String): String? {
        if (!pkg.startsWith("$ROOT.")) return null
        return bySegment[pkg.removePrefix("$ROOT.").substringBefore('.')]?.name
    }

    fun findings(
        root: File,
        scope: KoScope,
    ): List<String> {
        val files =
            scope.files.map { f ->
                SourceFile(
                    f.path.removePrefix(root.absolutePath).trimStart('/'),
                    f.packagee?.name.orEmpty(),
                    f.imports.map { it.name },
                )
            } + javaFiles(root)
        return importFindings(files) + ownershipFindings(root) + completenessFindings(root)
    }

    private class SourceFile(
        val path: String,
        val pkg: String,
        val imports: List<String>,
    )

    private fun importFindings(files: List<SourceFile>): List<String> =
        files.flatMap { file ->
            // The module is the one the package names; a file in a foreign package (a
            // waived one) is its directory's module.
            val from =
                (
                    moduleOfPackage(
                        file.pkg,
                    ) ?: file.path.substringBefore('/').takeIf { it in byName }
                )?.let { byName.getValue(it) }
                    ?: return@flatMap emptyList()
            val ok = allowed(from)
            file.imports.mapNotNull { imp ->
                if (!imp.startsWith("$ROOT.")) return@mapNotNull null
                val segment = imp.removePrefix("$ROOT.").substringBefore('.')
                val to = bySegment[segment]
                when {
                    to == null -> {
                        "${file.path}: '${from.name}' imports $imp, a platform package of no module in the model map"
                    }

                    to.name !in ok -> {
                        "${file.path}: '${from.name}' layer does not depends on '${to.name}' layer " +
                            "(${from.level} ${from.key} imports ${to.level} ${to.key}: $imp)"
                    }

                    else -> {
                        null
                    }
                }
            }
        }

    private val packageRegex = Regex("""(?m)^\s*package\s+([\w.`]+)""")

    /** Every `import` token, not only line starts: a Java line may hold several imports. */
    private val importRegex = Regex("""\bimport\s+(?:static\s+)?([\w.]+)""")

    /**
     * [text] with block comments (nested, as Kotlin allows), line comments and string and character literals blanked
     * out, newlines kept, so that a `package` or `import` inside a comment or a string is never read as a declaration.
     */
    fun code(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val end = tokenEnd(text, i)
            if (end == null) {
                i++
                continue
            }
            for (k in i until end) if (text[k] != '\n') out.setCharAt(k, ' ')
            i = end
        }
        return out.toString()
    }

    /** The end of the comment or literal that starts at [i], or null when none starts there. */
    private fun tokenEnd(
        text: String,
        i: Int,
    ): Int? =
        when {
            text.startsWith("/*", i) -> blockEnd(text, i)
            text.startsWith("//", i) -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
            text.startsWith("\"\"\"", i) -> textBlockEnd(text, i)
            text[i] == '"' || text[i] == '\'' -> quoteEnd(text, i)
            else -> null
        }

    /** The end of the block comment at [from]; Kotlin block comments nest. */
    private fun blockEnd(
        text: String,
        from: Int,
    ): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }

                text.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }

                else -> {
                    i++
                }
            }
        }
        return text.length
    }

    private fun textBlockEnd(
        text: String,
        from: Int,
    ): Int = text.indexOf("\"\"\"", from + 3).let { if (it < 0) text.length else it + 3 }

    /** The end of the string or character literal at [from], at its closing quote or the end of its line. */
    private fun quoteEnd(
        text: String,
        from: Int,
    ): Int {
        var i = from + 1
        while (i < text.length && text[i] != text[from] && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
        return minOf(if (i < text.length && text[i] == text[from]) i + 1 else i, text.length)
    }

    /** Layered module directories under [root]: every directory with a build file that declares a layer. */
    private fun moduleDirs(root: File): Map<String, File> =
        (
            root.listFiles().orEmpty().filter {
                it.isDirectory &&
                    File(
                        it,
                        "build.gradle.kts",
                    ).isFile
            }
        ).associateBy { it.name }

    /** Every Kotlin or Java file of a module directory, skipping build output and nested projects. */
    private fun sources(moduleDir: File): List<File> =
        moduleDir
            .walkTopDown()
            .onEnter { it == moduleDir || (it.name != "build" && !File(it, "build.gradle.kts").isFile) }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

    private fun javaFiles(root: File): List<SourceFile> =
        moduleDirs(root).values.flatMap { dir ->
            sources(dir).filter { it.extension == "java" }.map { f ->
                val text = code(f.readText())
                SourceFile(
                    f.relativeTo(root).invariantSeparatorsPath,
                    packageRegex
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty(),
                    importRegex.findAll(text).map { it.groupValues[1] }.toList(),
                )
            }
        }

    private fun ownershipFindings(root: File): List<String> {
        val out = mutableListOf<String>()
        val layered = declaredLayers(root).keys
        moduleDirs(root).filterKeys { it in layered }.forEach { (name, dir) ->
            val owned = "$ROOT.${name.replace("-", "")}"
            sources(dir).forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                val pkg = declaredPackage(f)?.replace("`", "")
                when {
                    pkg == null -> out += "$rel: declares no package, not under $owned"
                    pkg == owned || pkg.startsWith("$owned.") -> Unit
                    OWNERSHIP_EXCEPTIONS.any { it.path == rel && it.packageName == pkg } -> Unit
                    else -> out += "$rel: package $pkg is not under $owned (package ownership, INV-Q-4734)"
                }
            }
        }
        return out + staleExceptions(root)
    }

    /** The package a file declares, read from its code (comments and strings blanked), or null. */
    private fun declaredPackage(f: File): String? = packageRegex.find(code(f.readText()))?.groupValues?.get(1)

    private fun staleExceptions(root: File): List<String> =
        OWNERSHIP_EXCEPTIONS.mapNotNull { e ->
            val f = File(root, e.path)
            val pkg = if (f.isFile) declaredPackage(f) else null
            when {
                !f.isFile -> {
                    "allowlisted file ${e.path} is absent: remove the stale ownership exception"
                }

                pkg != e.packageName -> {
                    "allowlisted file ${e.path} declares package $pkg, not ${e.packageName}: " +
                        "stale ownership exception"
                }

                else -> {
                    null
                }
            }
        }

    private val layerRegex = Regex("""layer\s*=\s*(?:gst\.build\.)?Layer\.(L[123])\b""")

    /** The layer each module directory declares, from its build file or its generated gst/layer.properties. */
    private fun declaredLayers(root: File): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val dirs = moduleDirs(root).toMutableMap()
        includedModules(root).forEach { (name, dir) -> dirs.putIfAbsent(name, dir) }
        dirs.forEach { (name, dir) ->
            val build = File(dir, "build.gradle.kts").takeIf { it.isFile }?.readText().orEmpty()
            val props = File(dir, "build/generated/gst-layer/gst/layer.properties")
            val layer =
                layerRegex.find(build)?.groupValues?.get(1)
                    ?: props
                        .takeIf { it.isFile }
                        ?.readLines()
                        ?.firstOrNull {
                            it.startsWith(
                                "layer=",
                            )
                        }?.removePrefix("layer=")
                        ?.trim()
            if (layer != null) out[name] = layer
        }
        return out
    }

    /**
     * Project name to project directory, parsed from settings.gradle.kts: `include("a", ...)` and `"name"
     * to "dir"` pairs.
     */
    private fun includedModules(root: File): Map<String, File> {
        val text = File(root, "settings.gradle.kts").readText().lines().joinToString("\n") { it.substringBefore("//") }
        val out = linkedMapOf<String, File>()
        Regex("""include\s*\(([^)]*)\)""").findAll(text).forEach { m ->
            Regex(""""([^"]+)"""").findAll(m.groupValues[1]).forEach {
                out[it.groupValues[1]] =
                    File(root, it.groupValues[1])
            }
        }
        Regex(""""([^"]+)"\s+to\s+"([^"]+)"""").findAll(text).forEach {
            out[it.groupValues[1]] = File(root, "product-runtime/contracts/${it.groupValues[2]}")
        }
        return out
    }

    private fun completenessFindings(root: File): List<String> {
        val out = mutableListOf<String>()
        val declared = declaredLayers(root)
        declared.forEach { (name, layer) ->
            val entry = byName[name]
            when {
                entry == null -> {
                    out += "'$name' declares gstLibrary.layer but is not in the model map"
                }

                entry.level.name != layer -> {
                    out +=
                        "'$name' declares layer $layer but the model map says ${entry.level.name}"
                }
            }
        }
        includedModules(root).keys.forEach { name ->
            if (name !in declared && name !in UNLAYERED) {
                out +=
                    "'$name' is included in settings.gradle.kts but declares no gstLibrary.layer and is not one of the unlayered modules"
            }
        }
        return out
    }
}

internal fun assertClean(repo: Repo) {
    val findings = Rules.findings(repo.dir, Konsist.scopeFromExternalDirectory(repo.dir.absolutePath))
    if (findings.isNotEmpty()) fail<Unit>(findings.joinToString("\n"))
}

internal fun fails(
    repo: Repo,
    expected: String,
) {
    val findings = Rules.findings(repo.dir, Konsist.scopeFromExternalDirectory(repo.dir.absolutePath))
    assertTrue(findings.any { expected in it }) { "expected a finding containing '$expected', got $findings" }
}

/**
 * One stub file for every module of [MODEL] (L1, L2 and L3: crypto and tenancy-jooq too, not only the eight STK-60
 * modules), a layer declaration for each, and the unlayered modules of [UNLAYERED] with none; plus the two allowlisted
 * tenancy-jooq files. [withoutFiles] leaves a module with a build file and no sources.
 */
internal fun writeL1ModuleStubs(
    dir: File,
    withoutFiles: Set<String> = emptySet(),
): Repo {
    val repo = Repo(dir)
    MODEL.forEach { m ->
        repo.module(m.name, m.level.name)
        if (m.name !in withoutFiles) {
            val segment = m.name.replace("-", "")
            repo.file(
                m.name,
                "src/main/kotlin/com/dynokore/platform/$segment/Stub.kt",
                "package com.dynokore.platform.$segment\nclass Stub\n",
            )
        }
    }
    repo.module("platform-libs-bom", layer = null)
    repo.module("contract-tests", layer = null)
    listOf(
        "tenancy-client",
        "access-client",
        "structure-events",
        "profile-events",
        "authority-events",
        "policy-events",
    ).forEach { repo.module("product-runtime-$it", layer = null, pairDir = it) }
    OWNERSHIP_EXCEPTIONS.forEach {
        repo.file(
            it.path.substringBefore('/'),
            it.path.substringAfter('/'),
            "package ${it.packageName};\nclass ${it.path.substringAfterLast('/').removeSuffix(".java")} {}\n",
        )
    }
    return repo
}
