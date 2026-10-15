package com.dynokore.platform.contracttests

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * One module of the shared-pool release: the task, the release that ships it, and the literals that prove it in that
 * release's CHANGELOG section (the task key; audit-log has no entry that names its key, so it is fixed by content).
 */
data class BomCase(
    val module: String,
    val task: String,
    val release: String,
    val contentLiterals: List<String> = listOf(task),
)

internal val BOM_CASES =
    listOf(
        BomCase("outbox-and-queue", "INV-T-3979", "2.0.0"),
        BomCase("tenancy-kernel", "INV-T-3999", "2.0.0"),
        BomCase("service-runtime", "INV-T-3938", "7.0.0"),
        BomCase("config-resolver", "INV-T-3983", "2.0.0"),
        BomCase("tenancy-jooq", "INV-T-4133", "1.0.0"),
        BomCase("rulepack-kernel", "INV-T-4137", "2.0.0"),
        BomCase("audit-log", "INV-T-3984", "2.2.0", listOf("AuditStager", "purgeDdl", "purge_audit_entries")),
    )

/** The seven modules the task names; a module with no case in [BOM_CASES] fails [BomTestedSetTest]. */
internal val BOM_MODULES =
    listOf(
        "outbox-and-queue",
        "tenancy-kernel",
        "service-runtime",
        "config-resolver",
        "tenancy-jooq",
        "rulepack-kernel",
        "audit-log",
    )

/** Numeric SemVer ordering of X.Y.Z (2.10.0 is after 2.9.0); anything else is not a version. */
internal fun semver(text: String): List<Int>? {
    val parts = text.split('.')
    if (parts.size != 3) return null
    val numbers = parts.map { if (it.isNotEmpty() && it.all(Char::isDigit)) it.toIntOrNull() else null }
    return if (numbers.any { it == null }) null else numbers.filterNotNull()
}

internal fun compareSemver(
    a: List<Int>,
    b: List<Int>,
): Int = a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0

private val RELEASED = Regex("""^## \[([^\]]+)] - \d{4}-\d{2}-\d{2}$""")
private val REVERT = Regex("""\brevert(s)?\b""", RegexOption.IGNORE_CASE)

/** Released CHANGELOG sections as version to section text; an `Unreleased` (or any non-X.Y.Z) heading never counts. */
internal fun releasedSections(changelog: String): Map<String, String> {
    val sections = linkedMapOf<String, StringBuilder>()
    var current: StringBuilder? = null
    for (line in changelog.lines()) {
        if (line.startsWith("## ")) {
            val version = RELEASED.matchEntire(line)?.groupValues?.get(1)
            current =
                if (version != null &&
                    semver(version) != null
                ) {
                    StringBuilder().also { sections[version] = it }
                } else {
                    null
                }
        } else {
            current?.appendLine(line)
        }
    }
    return sections.mapValues { it.value.toString() }
}

private fun hasKey(
    text: String,
    literal: String,
) = Regex("(?<![A-Za-z0-9-])" + Regex.escape(literal) + "(?![0-9A-Za-z])").containsMatchIn(text)

/** All violations of one module's case against the checkout at [root]; empty means the BOM carries the change. */
internal fun violations(
    root: File,
    case: BomCase,
): List<String> {
    val out = mutableListOf<String>()
    val module = case.module
    val sections = releasedSections(File(root, "$module/CHANGELOG.md").readText())
    val named = sections[case.release]
    if (named == null) {
        out += "$module: no released heading [${case.release}]"
    } else {
        case.contentLiterals.filterNot { hasKey(named, it) }.forEach {
            out +=
                "$module ${case.release}: section lacks $it"
        }
    }
    val version = File(root, "$module/version.txt").readText().trim()
    val current = semver(version)
    val releaseVersion = semver(case.release)!!
    if (current == null || version !in sections) {
        out += "$module: version.txt '$version' is not a released CHANGELOG heading"
    } else if (compareSemver(current, releaseVersion) < 0) {
        out += "$module: version $version is before ${case.release}"
    } else {
        for ((v, text) in sections) {
            val parsed = semver(v)!!
            val later = compareSemver(parsed, releaseVersion) > 0 && compareSemver(parsed, current) <= 0
            if (later && hasKey(text, case.task) && REVERT.containsMatchIn(text)) {
                out += "$module $v: section names ${case.task} and says revert"
            }
        }
    }
    val bom = File(root, "platform-libs-bom/build.gradle.kts").readText()
    for (artifact in listOf("$module-api", module)) {
        val pair = """api("com.dynokore.platform:$artifact:${'$'}{project(":$module").version}")"""
        if (!bom.contains(pair)) out += "BOM lacks the pair for $artifact"
    }
    return out
}

internal fun literalBomVersions(bom: String): List<String> =
    Regex("""api\("com\.dynokore\.platform:[^:"]+:\d[^"]*"\)""").findAll(bom).map { it.value }.toList()

/**
 * INV-T-3989 (INV-REQ-1225, coordinator ruling INV-Q-5089): for each of the seven shared-pool modules, the version the
 * BOM carries (the module's version.txt) is a released CHANGELOG version at or after the release that names the task,
 * the task was not reverted since, and the BOM reads that version. Fails closed.
 */
class BomTestedSetTest {
    private val root = File(System.getProperty("contracttests.repoRoot"))

    companion object {
        @JvmStatic
        fun cases() = BOM_CASES

        private fun copy(
            from: File,
            into: File,
        ) {
            for (m in BOM_MODULES) {
                File(into, m).mkdirs()
                for (f in listOf("version.txt", "CHANGELOG.md")) File(from, "$m/$f").copyTo(File(into, "$m/$f"))
            }
            File(into, "platform-libs-bom").mkdirs()
            File(from, "platform-libs-bom/build.gradle.kts").copyTo(File(into, "platform-libs-bom/build.gradle.kts"))
        }
    }

    @Test
    fun `a case exists for exactly the seven modules`() {
        BOM_CASES.map { it.module }.sorted() shouldBe BOM_MODULES.sorted()
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `the BOM carries the change of the release that shipped the task`(case: BomCase) {
        violations(root, case).shouldBeEmpty()
    }

    @Test
    fun `no BOM constraint carries a literal platform-libs version`() {
        literalBomVersions(File(root, "platform-libs-bom/build.gradle.kts").readText()).shouldBeEmpty()
    }

    @Test
    fun `numeric comparison puts 2_10_0 after 2_9_0`() {
        (compareSemver(semver("2.10.0")!!, semver("2.9.0")!!) > 0) shouldBe true
    }

    // The probes below run the same checks on a mutated copy: each must be found.
    @Test
    fun `probes are found on a mutated copy`(
        @TempDir tmp: File,
    ) {
        val case = BOM_CASES.first { it.module == "outbox-and-queue" }
        val bomFile = File(tmp, "platform-libs-bom/build.gradle.kts")

        copy(root, tmp)
        violations(tmp, case).shouldBeEmpty()

        val changelog = File(tmp, "${case.module}/CHANGELOG.md")
        val original = changelog.readText()
        changelog.writeText(original.replace(case.task, "INV-T-0000"))
        violations(tmp, case).shouldNotBeEmpty()
        changelog.writeText(original)

        val version = File(tmp, "${case.module}/version.txt")
        version.writeText("1.9.9\n")
        violations(tmp, case).shouldNotBeEmpty()
        version.writeText("99.0.0\n")
        violations(tmp, case).shouldNotBeEmpty()
        version.writeText(File(root, "${case.module}/version.txt").readText())
        violations(tmp, case).shouldBeEmpty()

        changelog.writeText(
            original.replaceFirst("## [Unreleased]", "## [Unreleased]\n- ${case.task} reverts the change"),
        )
        violations(tmp, case).shouldBeEmpty() // Unreleased never counts
        val latest = File(root, "${case.module}/version.txt").readText().trim()
        changelog.writeText(
            Regex(
                "(?m)^(## \\[" + Regex.escape(latest) + "] - .*)$",
            ).replace(original) { "${it.value}\n- ${case.task} reverts\n" },
        )
        violations(tmp, case).shouldNotBeEmpty()
        changelog.writeText(original)

        val bom = bomFile.readText()
        literalBomVersions(bom.replaceFirst("""${'$'}{project(":${case.module}").version}""", "1.0.0"))
            .shouldNotBeEmpty()
        bomFile.writeText(
            bom.replace("""api("com.dynokore.platform:${case.module}:""", """api("com.dynokore.platform:x:"""),
        )
        violations(tmp, case).shouldNotBeEmpty()
    }
}
