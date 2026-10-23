package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contract pin and the tables of INV-T-8850 (INV-AC-8848). The bundle suffix is compared by equality with the first
 * 8 characters of the platform-contracts main commit that merged INV-T-8849; the operations
 * row and the criterion row are compared by equality on the task key.
 */
class TokenPinsTest {
    private val toml = File("../gradle/libs.versions.toml").readText()
    private val operations = File("../operations.tsv").readLines()
    private val keys = File("../ac-keys.tsv").readLines()

    private fun bundleSuffix(text: String): String? =
        Regex("""(?m)^platformContractsBundle\s*=\s*"0\.0\.0-dev\+([^"]*)"""").find(text)?.groupValues?.get(1)

    private fun bundleFindings(
        text: String,
        mergeCommit: String,
        openapi: String,
    ): List<String> =
        buildList {
            if (bundleSuffix(text) != mergeCommit.take(SHORT)) add("bundle suffix is not ${mergeCommit.take(SHORT)}")
            if (!Regex("""operationId:\s*issueAccessToken\s*$""", RegexOption.MULTILINE).containsMatchIn(openapi)) {
                add("operation issueAccessToken is absent")
            }
        }

    private fun rowFindings(
        lines: List<String>,
        name: String,
        task: String,
    ): List<String> {
        val rows = lines.map { it.split('\t') }.filter { it.size > 1 && it[1] == name }
        return when {
            rows.size != 1 -> listOf("$name has ${rows.size} rows")
            rows.single()[2] != task -> listOf("$name names ${rows.single()[2]}")
            else -> emptyList()
        }
    }

    private val openapi =
        checkNotNull(TokenPinsTest::class.java.classLoader.getResourceAsStream("gst/contracts/access/openapi.yaml")) {
            "the staged access contract is missing"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `INV-AC-8848 the bundle pin equals the INV-T-8849 merge commit and the contract holds issueAccessToken`() {
        assertThat(bundleFindings(toml, MERGE_COMMIT, openapi)).isEmpty()
        // probes: the bundle of f4cb676f has no issueAccessToken; a look-alike suffix of another length differs
        assertThat(bundleFindings(toml.replace(MERGE_COMMIT.take(SHORT), "f4cb676f"), MERGE_COMMIT, openapi))
            .containsExactly("bundle suffix is not 21fc1bf0")
        assertThat(bundleFindings(toml, MERGE_COMMIT, openapi.replace("issueAccessToken", "x")))
            .containsExactly("operation issueAccessToken is absent")
    }

    @Test
    fun `INV-AC-8848 the operations row names INV-T-8850 and ac-keys holds the criterion row for wave 2`() {
        assertThat(rowFindings(operations, "issueAccessToken", "INV-T-8850")).isEmpty()
        assertThat(rowFindings(operations, "issueAccessToken", "UNASSIGNED")).isNotEmpty()
        val lookAlike = operations.map { it.replace("INV-T-8850", "INV-T-88500") }
        assertThat(rowFindings(lookAlike, "issueAccessToken", "INV-T-8850")).isNotEmpty()
        val row = keys.map { it.split('\t') }.single { it[0] == "INV-AC-8848" }
        assertThat(row).containsExactly("INV-AC-8848", "INV-T-8850", "2")
    }

    private companion object {
        const val MERGE_COMMIT = "21fc1bf0"
        const val SHORT = 8
    }
}
