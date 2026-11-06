package com.dynokore.gst.transport.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * INV-AC-17672 (INV-Q-17667 A, INV-Q-17669 A): the resolved `com.dynokore.gst:gst-catalogue` equals the pin of
 * `gradle/libs.versions.toml` in every lockfile, and the generated `ErrorCode` holds `SUB-024` and not `SUB-0240`.
 */
class GstCataloguePinTest {
    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `INV-AC-17672 the lockfiles hold one gst-catalogue entry at the pin and the generated ErrorCode holds SUB-024`() {
        val root = File(System.getProperty("gst.root.dir"))
        val lockfiles = LOCKFILES.associateWith { File(root, it).takeIf { f -> f.isFile }?.readText().orEmpty() }
        val toml = File(root, TOML).takeIf { it.isFile }?.readText().orEmpty()
        val enumType = Class.forName("com.dynokore.gst.catalogue.ErrorCode")
        val getCode = enumType.getMethod("getCode")
        val codes = enumType.enumConstants.map { getCode.invoke(it) as String }
        findings(lockfiles, toml, codes).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-17672 the check reports a finding for each lockfile probe`() {
        findings(BASELINE, TOML_TEXT, CODES).shouldBeEmpty()
        val bootstrap = "transport-bootstrap/gradle.lockfile"
        val acceptance = "acceptance/gradle.lockfile"
        val probes =
            mapOf(
                "added 2.11.0 entry" to
                    (
                        bootstrap to
                            (BASELINE.getValue(bootstrap) + "com.dynokore.gst:gst-catalogue:2.11.0=runtimeClasspath\n")
                    ),
                "look-alike row only" to (acceptance to "com.dynokore.gst:gst-catalogue-api:2.35.0=runtimeClasspath\n"),
                "other version" to (acceptance to "com.dynokore.gst:gst-catalogue:2.34.0=testRuntimeClasspath\n"),
                "same name other group" to (acceptance to "com.other:gst-catalogue:2.35.0=testRuntimeClasspath\n"),
                "second entry in another module" to
                    (
                        "transport-domain/gradle.lockfile" to
                            "com.dynokore.gst:gst-catalogue:2.35.0=a\ncom.dynokore.gst:gst-catalogue:2.35.0=b\n"
                    ),
                "empty file" to (acceptance to ""),
            )
        probes.forEach { (name, change) ->
            val result = findings(BASELINE + change, TOML_TEXT, CODES)
            result.shouldNotBeEmpty()
            (result.any { it.contains(change.first) }) shouldBe true
            println("probe '$name' -> $result")
        }
        findings(BASELINE, TOML_TEXT.replace("\"2.35.0\"", "\"2.36.0\""), CODES).shouldNotBeEmpty()
        findings(BASELINE - acceptance, TOML_TEXT, CODES).any { it.contains(acceptance) } shouldBe true
        findings(BASELINE, "[versions]\n", CODES).any { it.contains(TOML) } shouldBe true
        findings(BASELINE, "", CODES).any { it.contains(TOML) } shouldBe true
    }

    @Test
    fun `INV-AC-17672 the check reports a finding when the codes hold SUB-0240 and not SUB-024`() {
        findings(BASELINE, TOML_TEXT, listOf("SUB-0240")).shouldNotBeEmpty()
        findings(BASELINE, TOML_TEXT, listOf("SUB-02")).shouldNotBeEmpty()
        findings(BASELINE, TOML_TEXT, emptyList()).shouldNotBeEmpty()
        findings(BASELINE, TOML_TEXT, listOf("SUB-024", "SUB-0240")).shouldNotBeEmpty()
        findings(BASELINE, TOML_TEXT, listOf("SUB-023", "SUB-024")).shouldBeEmpty()
        findings(BASELINE, TOML_TEXT, emptyList()).joinToString() shouldContain "SUB-024"
    }

    companion object {
        private const val TOML = "gradle/libs.versions.toml"
        private const val GROUP = "com.dynokore.gst"
        private const val NAME = "gst-catalogue"
        private val EXACTLY_ONE = setOf("transport-bootstrap/gradle.lockfile", "acceptance/gradle.lockfile")
        private val LOCKFILES =
            listOf(
                "transport-adapters/gradle.lockfile",
                "transport-application/gradle.lockfile",
                "transport-bootstrap/gradle.lockfile",
                "transport-contract/gradle.lockfile",
                "transport-domain/gradle.lockfile",
                "acceptance/gradle.lockfile",
            )
        private val CODES = listOf("SUB-023", "SUB-024")
        private const val TOML_TEXT = "[versions]\ngstCatalogue = \"2.35.0\"\nother = \"1\"\n\n[libraries]\nx = \"y\"\n"
        private val BASELINE =
            LOCKFILES.associateWith {
                if (it in
                    EXACTLY_ONE
                ) {
                    "# lock\n$GROUP:$NAME:2.35.0=testRuntimeClasspath\nfoo:bar:1=x\n"
                } else {
                    "foo:bar:1=x\n"
                }
            }

        /** The pin: value of the key `gstCatalogue` in the `[versions]` table, or null. */
        private fun pin(toml: String): String? {
            var inVersions = false
            for (raw in toml.lines()) {
                val line = raw.trim()
                if (line.startsWith("[")) inVersions = line == "[versions]"
                if (!inVersions) continue
                val parts = line.split("=", limit = 2)
                if (parts.size == 2 && parts[0].trim() == "gstCatalogue") return parts[1].trim().removeSurrounding("\"")
            }
            return null
        }

        /** The versions of the exact coordinate `com.dynokore.gst:gst-catalogue` (string equality on group and name). */
        private fun entries(lock: String): List<String> =
            lock.lines().mapNotNull { line ->
                val coordinate = line.substringBefore('=', missingDelimiterValue = "").split(':')
                if (coordinate.size == 3 && coordinate[0] == GROUP && coordinate[1] == NAME) coordinate[2] else null
            }

        fun findings(
            lockfiles: Map<String, String>,
            toml: String,
            codes: Collection<String>,
        ): List<String> {
            val out = mutableListOf<String>()
            val pin = pin(toml)
            if (pin.isNullOrEmpty()) out += "$TOML: the key gstCatalogue is missing from [versions]"
            for (file in LOCKFILES) {
                val text = lockfiles[file]
                if (text.isNullOrBlank()) {
                    out += "$file: missing or empty"
                    continue
                }
                val versions = entries(text)
                if (file in EXACTLY_ONE &&
                    versions.size != 1
                ) {
                    out += "$file: ${versions.size} gst-catalogue entries, expected 1"
                }
                if (file !in EXACTLY_ONE &&
                    versions.size > 1
                ) {
                    out += "$file: ${versions.size} gst-catalogue entries, expected at most 1"
                }
                versions.filter { it != pin }.forEach { out += "$file: gst-catalogue $it differs from the pin $pin" }
            }
            if ("SUB-024" !in codes) out += "ErrorCode: SUB-024 is missing"
            if ("SUB-0240" in codes) out += "ErrorCode: SUB-0240 is present"
            return out
        }
    }
}
