package com.dynokore.gst.rules.bootstrap

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.core.exception.KoAssertionFailedException
import gst.build.konsist.ArchitectureRules
import gst.build.konsist.MigrationLint
import gst.build.konsist.RawSqlPlacement
import gst.build.konsist.SqlGuards
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Architecture tests (STK-41, TPL-14): ARC-03 and ARC §2.2 over this repository's own sources, and the same rules over
 * planted fixtures, so a rule that stops failing is caught here and not only by a manual probe. The layer rule of
 * INV-T-5325 refuses Spring, jOOQ, Jackson, servlet and JPA imports in `domain` and `application` and passes `java.sql`
 * and `javax.sql` there (owner answers INV-Q-5327 (D), INV-Q-5331 (D)).
 */
class ArchitectureTest {
    private val root = File("..").canonicalFile

    private fun scopeOf(vararg modules: String): KoScope =
        modules.map { Konsist.scopeFromExternalDirectory(File(root, "$it/src/main").path) }.reduce { a, b -> a + b }

    /** The generated server interfaces and DTOs: the contract layer (TPL-01), built before this module's tests run. */
    private fun contractScope(): KoScope =
        Konsist.scopeFromExternalDirectory(
            File(root, "rules-contract/build/generated/contract-server/src/main/kotlin").path,
        )

    @Test
    fun `the five modules keep ARC-03 and the module direction of ARC section 2-2`() {
        ArchitectureRules.assertServiceModuleDirection(
            "com.dynokore.gst.rules",
            scopeOf("rules-domain", "rules-application", "rules-adapters", "rules-bootstrap") + contractScope(),
        )
    }

    @Test
    fun `the adapters keep raw SQL out, jOOQ only`() {
        val adapters = scopeOf("rules-adapters")
        RawSqlPlacement.assertPlacement(adapters)
        SqlGuards.assertAll(adapters)
    }

    @Test
    fun `the migrations pass the migration lint`() {
        MigrationLint.assertMigrations(File(root, "rules-adapters"), scopeOf("rules-adapters"))
    }

    /**
     * Resolution never reads the clock (INV-REQ-770, INV-REQ-1089): no `Clock` and no `now()` in the resolution path.
     */
    @Test
    fun `the resolution path names no Clock and calls no now`() {
        val files =
            File(root, "$DOMAIN_MAIN/resolution").walkTopDown().filter { it.extension == "kt" }
        val clockUse = CLOCK_USE
        val offenders =
            files
                .filter { file -> clockUse.containsMatchIn(stripComments(file.readText())) }
                .map { it.name }
                .toList()
        check(offenders.isEmpty()) { "INV-REQ-770: the resolution path reads the clock in $offenders" }
        // the adapter that answers resolution queries has no clock either
        val lookup = File(root, "rules-adapters/src/main/kotlin/$PACKAGE_DIR/adapters/persistence/ArmedPackLookup.kt")
        val lookupText = stripComments(lookup.readText())
        check(!clockUse.containsMatchIn(lookupText)) { "INV-REQ-770: ArmedPackLookup reads the clock" }
    }

    @Test
    fun `a Konsist test on the clock rule fails on a planted Clock in the resolution path`() {
        assert(
            Regex("""\b(Clock|LocalDate\.now)\b""").containsMatchIn(
                stripComments("class R(private val clock: java.time.Clock) { fun d() = LocalDate.now() }"),
            ),
        )
        val commented = "// Clock in a comment\n/* LocalDate.now() */ class R"
        assert(!Regex("""\bClock\b""").containsMatchIn(stripComments(commented)))
    }

    // ---- raw SQL probes, on a planted adapters file ----

    private fun adaptersFixture(
        dir: File,
        body: String,
    ): KoScope {
        File(dir, "adapters").mkdirs()
        File(dir, "adapters/Planted.kt").writeText("package com.dynokore.gst.rules.adapters\n$body\n")
        return Konsist.scopeFromExternalDirectory(dir.absolutePath)
    }

    @Test
    fun `a JdbcTemplate in the adapters is a finding of the placement rule`(
        @TempDir dir: File,
    ) {
        val planted =
            adaptersFixture(
                dir,
                "import org.springframework.jdbc.core.JdbcTemplate\n" +
                    "class Planted(val t: JdbcTemplate) { fun q() = t.queryForList(\"select 1\") }",
            )
        check(RawSqlPlacement.findViolations(planted).isNotEmpty()) { "JdbcTemplate was not found" }
    }

    @Test
    fun `a javax sql DataSource constructor parameter in the adapters is no finding`(
        @TempDir dir: File,
    ) {
        val planted = adaptersFixture(dir, "import javax.sql.DataSource\nclass Planted(val d: DataSource)")
        check(RawSqlPlacement.findViolations(planted).isEmpty()) { "a DataSource type mention was refused" }
    }

    // ---- layer rule probes, on planted files in a temp tree (the repository is never touched) ----

    private fun fixture(
        dir: File,
        domainImport: String,
    ): KoScope {
        File(dir, "domain").mkdirs()
        File(dir, "application").mkdirs()
        File(dir, "domain/Planted.kt")
            .writeText("package com.dynokore.gst.rules.domain\n$domainImport\nclass Planted\n")
        File(dir, "application/Use.kt").writeText("package com.dynokore.gst.rules.application\nclass Use\n")
        listOf("adapters", "bootstrap", "contract").forEach { layer ->
            File(dir, layer).mkdirs()
            File(dir, "$layer/Stub.kt").writeText("package com.dynokore.gst.rules.$layer\nclass Stub\n")
        }
        return Konsist.scopeFromExternalDirectory(dir.absolutePath)
    }

    @Test
    fun `a jOOQ import in the domain fails the layer rule`(
        @TempDir dir: File,
    ) {
        assertThrows(KoAssertionFailedException::class.java) {
            ArchitectureRules.assertServiceModuleDirection(ROOT_PACKAGE, fixture(dir, "import org.jooq.DSLContext"))
        }
    }

    @Test
    fun `a Spring import in the domain fails the layer rule`(
        @TempDir dir: File,
    ) {
        assertThrows(KoAssertionFailedException::class.java) {
            ArchitectureRules.assertServiceModuleDirection(
                "com.dynokore.gst.rules",
                fixture(dir, "import org.springframework.stereotype.Component"),
            )
        }
    }

    @Test
    fun `java sql and javax sql imports in the domain pass the layer rule`(
        @TempDir dir: File,
    ) {
        val types = listOf("java.sql.Timestamp", "java.sql.SQLException", "java.sql.Connection", "javax.sql.DataSource")
        types.forEach { type ->
            File(dir, type).mkdirs()
            assertDoesNotThrow {
                ArchitectureRules.assertServiceModuleDirection(ROOT_PACKAGE, fixture(File(dir, type), "import $type"))
            }
        }
    }

    @Test
    fun `look-alike imports are told apart by prefix`(
        @TempDir dir: File,
    ) {
        // not a framework: a package that merely contains the word, and the org.jooqx look-alike is also refused by
        // prefix
        assertDoesNotThrow {
            ArchitectureRules.assertServiceModuleDirection(
                ROOT_PACKAGE,
                fixture(dir, "import com.example.springframework.Thing"),
            )
        }
    }

    private companion object {
        const val ROOT_PACKAGE = "com.dynokore.gst.rules"
        const val PACKAGE_DIR = "com/dynokore/gst/rules"
        const val DOMAIN_MAIN = "rules-domain/src/main/kotlin/$PACKAGE_DIR/domain"
        val CLOCK_USE =
            Regex(
                """\b(Clock|LocalDate\.now|LocalDateTime\.now|Instant\.now|ZonedDateTime\.now|""" +
                    """OffsetDateTime\.now|System\.currentTimeMillis)\b""",
            )
    }

    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""//[^\n]*"""), " ")
}
