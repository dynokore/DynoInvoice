package com.dynokore.gst.transport.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * INV-AC-17912 (INV-T-15724 design question, option A): the generated caller-auth operations list and the single
 * classpath copy both equal the ruled text, compared by string equality.
 */
class CallerAuthOperationsTest {
    @Test
    fun `INV-AC-17912 the generated list and the single classpath resource equal the ruled text`() {
        val root = File(System.getProperty("gst.root.dir"))
        val generated = File(root, GENERATED).takeIf { it.isFile }?.readText()
        val resources =
            Thread
                .currentThread()
                .contextClassLoader
                .getResources(RESOURCE)
                .toList()
                .map { it.readText() }
        findings(generated, resources).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-17912 the check reports a finding naming the source for each in-memory probe`() {
        findings(RULED, listOf(RULED)).shouldBeEmpty()
        val probes =
            listOf(
                Probe("generated file missing", null, listOf(RULED), SRC_GENERATED),
                Probe("generated file empty", "", listOf(RULED), SRC_GENERATED),
                Probe("generated second line", RULED + RULED, listOf(RULED), SRC_GENERATED),
                Probe("generated value end-user", RULED + "POST\t$PATH\tend-user\n", listOf(RULED), SRC_GENERATED),
                Probe(
                    "generated value signed-callbacks",
                    line(METHOD, PATH, "signed-callbacks"),
                    listOf(RULED),
                    SRC_GENERATED,
                ),
                Probe(
                    "generated path without final s",
                    line(METHOD, PATH.dropLast(1), VALUE),
                    listOf(RULED),
                    SRC_GENERATED,
                ),
                Probe("generated method GET", line("GET", PATH, VALUE), listOf(RULED), SRC_GENERATED),
                Probe("generated no final line feed", RULED.dropLast(1), listOf(RULED), SRC_GENERATED),
                Probe("resource empty text", RULED, listOf(""), SRC_RESOURCE),
                Probe("resource value end-user", RULED, listOf(RULED + "POST\t$PATH\tend-user\n"), SRC_RESOURCE),
                Probe(
                    "resource value signed-callbacks",
                    RULED,
                    listOf(line(METHOD, PATH, "signed-callbacks")),
                    SRC_RESOURCE,
                ),
                Probe(
                    "resource path without final s",
                    RULED,
                    listOf(line(METHOD, PATH.dropLast(1), VALUE)),
                    SRC_RESOURCE,
                ),
                Probe("resource method GET", RULED, listOf(line("GET", PATH, VALUE)), SRC_RESOURCE),
                Probe("resource no final line feed", RULED, listOf(RULED.dropLast(1)), SRC_RESOURCE),
                Probe("resource list empty", RULED, emptyList(), SRC_RESOURCE),
                Probe("resource list twice", RULED, listOf(RULED, RULED), SRC_RESOURCE),
            )
        probes.forEach { p ->
            val result = findings(p.generated, p.resources)
            result.shouldNotBeEmpty()
            (result.any { it.contains(p.source) }) shouldBe true
            println("probe '${p.name}' -> $result")
        }
    }

    private data class Probe(
        val name: String,
        val generated: String?,
        val resources: List<String>,
        val source: String,
    )

    companion object {
        private const val GENERATED =
            "transport-contract/build/generated/caller-auth/META-INF/gst/caller-auth-operations.tsv"
        private const val RESOURCE = "META-INF/gst/caller-auth-operations.tsv"
        private const val SRC_GENERATED = "generated file"
        private const val SRC_RESOURCE = "classpath resource"
        private const val METHOD = "POST"
        private const val PATH = "/v1/ap/{adapterId}/notifications"
        private const val VALUE = "signed-callback"
        private val RULED = line(METHOD, PATH, VALUE)

        private fun line(
            method: String,
            path: String,
            value: String,
        ): String = "$method\t$path\t$value\n"

        /** Findings of the caller-auth operations check; each names its source. Empty means the ruled text everywhere. */
        fun findings(
            generated: String?,
            resources: List<String>,
        ): List<String> {
            val out = mutableListOf<String>()
            if (generated == null) {
                out += "$SRC_GENERATED is missing: $GENERATED"
            } else if (generated != RULED) {
                out += "$SRC_GENERATED differs from the ruled text: '$generated'"
            }
            if (resources.size != 1) {
                out += "$SRC_RESOURCE $RESOURCE must be present exactly once, found ${resources.size}"
            }
            resources.forEachIndexed { i, text ->
                if (text != RULED) out += "$SRC_RESOURCE #$i differs from the ruled text: '$text'"
            }
            return out
        }
    }
}
