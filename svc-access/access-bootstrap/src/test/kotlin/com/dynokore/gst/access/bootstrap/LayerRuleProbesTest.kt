package com.dynokore.gst.access.bootstrap

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.core.exception.KoAssertionFailedException
import gst.build.konsist.ArchitectureRules
import gst.build.konsist.RawSqlPlacement
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The probes of the layer and database-access rules as tests: each line is added alone to a class of the layer in a
 * fixture tree and the shared rule is run on it (owner answers INV-Q-5331 (D) and INV-Q-5327 (D)). The statement and
 * connection probes (`Connection.prepareStatement`, `DataSource.getConnection()`) are caught by the bytecode check
 * `verifyMainSqlPlacement` of gst.kotlin-base, not by a source rule; they were run once against the real module (see the
 * merge request).
 */
class LayerRuleProbesTest {
    private val root = "com.dynokore.gst.access"

    private fun fixture(
        dir: File,
        layer: String,
        body: String,
    ): File {
        listOf("domain", "application", "adapters", "bootstrap", "contract").forEach { name ->
            File(dir, "$name/Stub.kt").also { it.parentFile.mkdirs() }.writeText("package $root.$name\n\nclass Stub\n")
        }
        File(dir, "$layer/Probe.kt").writeText("package $root.$layer\n\n$body\n")
        return dir
    }

    private fun layerRule(dir: File) =
        ArchitectureRules.assertServiceModuleDirection(root, Konsist.scopeFromExternalDirectory(dir.absolutePath))

    @Test
    fun `a jOOQ or Spring import in the domain fails the layer rule`(
        @TempDir dir: File,
    ) {
        assertThrows(KoAssertionFailedException::class.java) {
            layerRule(fixture(dir, "domain", "import org.jooq.DSLContext\n\nclass Probe"))
        }
        assertThrows(KoAssertionFailedException::class.java) {
            layerRule(
                fixture(dir, "domain", "import org.springframework.stereotype.Component\n\n@Component\nclass Probe"),
            )
        }
        assertThrows(KoAssertionFailedException::class.java) {
            layerRule(fixture(dir, "domain", "import tools.jackson.databind.ObjectMapper\n\nclass Probe"))
        }
    }

    @Test
    fun `a java sql or javax sql import in the domain passes the layer rule`(
        @TempDir dir: File,
    ) {
        listOf(
            "import java.sql.Timestamp\n\nclass Probe(val at: Timestamp)",
            "import java.sql.SQLException\n\nclass Probe(val e: SQLException)",
            "import javax.sql.DataSource\n\nclass Probe(val source: DataSource)",
        ).forEach { body -> assertDoesNotThrow { layerRule(fixture(dir, "domain", body)) } }
    }

    private fun placement(
        dir: File,
        body: String,
    ) = RawSqlPlacement.findViolations(Konsist.scopeFromExternalDirectory(fixture(dir, "adapters", body).absolutePath))

    @Test
    fun `a JdbcTemplate call fails the adapters rule at source level`(
        @TempDir dir: File,
    ) {
        val probes =
            mapOf(
                "JdbcTemplate" to
                    "import org.springframework.jdbc.core.JdbcTemplate\n\nclass Probe(val t: JdbcTemplate) {\n" +
                    "    fun run() = t.queryForObject(\"select 1\", Int::class.java)\n}",
            )
        probes.forEach { (name, body) ->
            assertTrue(placement(dir, body).isNotEmpty(), "$name must be a finding")
        }
    }

    @Test
    fun `naming a javax sql DataSource as a constructor parameter passes the adapters rule`(
        @TempDir dir: File,
    ) {
        assertTrue(placement(dir, "import javax.sql.DataSource\n\nclass Probe(val source: DataSource)").isEmpty())
    }
}
