package com.dynokore.gst.controlPlane.adapters.corpus

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The scenario-dependent golden document (INV-AC-9790, INV-REQ-2660): `sm-sws-supply-tagged-invoice` reads the SCENARIO
 * baseline through the scheme-tagged entry of `mapping-scheme-fixture.json`, so a SCENARIO candidate changes its
 * classification. Every probe edits a copy of the committed tree.
 */
class ScenarioDependentCorpusTest {
    @TempDir
    lateinit var tmp: Path

    private val tree by lazy { CorpusTree(tmp) }
    private val docName = "sm-sws-supply-tagged-invoice"

    private fun scenario() = tree.text("scenario-pack.json")

    private fun tagged(corpus: InProcessConformanceCorpus) = corpus.documents().first { it.id.value == docName }

    private fun classified(
        dir: Path,
        pack: String = scenario(),
    ): String? {
        val corpus = tree.corpus(dir)
        return corpus.classify(tree.pack("SCENARIO", pack), tagged(corpus))
    }

    private fun refused(dir: Path) {
        shouldThrow<IllegalStateException> { tree.corpus(dir).documents() }
    }

    @Test
    fun `INV-AC-9790 the self-check gives sm-sws-supply-tagged-invoice the value ZR`() {
        val corpus = tree.corpus()
        tagged(corpus).expected shouldBe "ZR"
        corpus.classify(tree.pack("SCENARIO", scenario()), tagged(corpus)) shouldBe "ZR"
        tree.manifestNames().indexOf("mapping-scheme-fixture.json") shouldNotBe -1
        InProcessConformanceCorpus.FIXTURES.contains("mapping-scheme-fixture.json") shouldBe true
        InProcessConformanceCorpus.FIXTURES.contains(docName + ".json") shouldBe false
    }

    @Test
    fun `INV-AC-9790 a SCENARIO candidate whose SWS SUPPLY treatment is TX makes classify return TX not ZR`() {
        val corpus = tree.corpus()
        val candidate = scenario().replace("\"categoryCode\": \"ZR\"", "\"categoryCode\": \"TX\"")
        candidate shouldNotBe scenario()
        val value = corpus.classify(tree.pack("SCENARIO", candidate), tagged(corpus))
        value shouldBe "TX"
        value shouldNotBe "ZR"
        // look-alike that must pass: the candidate equal to the baseline
        corpus.classify(tree.pack("SCENARIO", scenario()), tagged(corpus)) shouldBe "ZR"
    }

    @Test
    fun `INV-AC-9790 a baseline with SWS treatment TX fails the self-check and the look-alike tag SWX is refused`() {
        val changed = tree.copy()
        tree.edit(changed, "scenario-pack.json") { it.replace("\"categoryCode\": \"ZR\"", "\"categoryCode\": \"TX\"") }
        val corpus = tree.corpus(changed)
        val baselineOnly = corpus.classify(tree.pack("CALENDAR", "{}"), tagged(corpus))
        baselineOnly shouldBe "TX"
        baselineOnly shouldNotBe tagged(corpus).expected

        val look = tree.copy()
        tree.edit(look, "scenario-pack.json") { it.replace("\"tag\": \"SWS\"", "\"tag\": \"SWX\"") }
        val lookCorpus = tree.corpus(look)
        shouldThrow<RuntimeException> { lookCorpus.classify(tree.pack("CALENDAR", "{}"), tagged(lookCorpus)) }
    }

    @Test
    fun `INV-AC-9790 scheme fixture absent, changed by one byte or under a look-alike name refuses documents`() {
        val absent = tree.copy()
        Files.delete(absent.resolve("mapping-scheme-fixture.json"))
        refused(absent)
        val absentRehashed = tree.copy()
        Files.delete(absentRehashed.resolve("mapping-scheme-fixture.json"))
        tree.rehash(absentRehashed)
        refused(absentRehashed)
        val byte = tree.copy()
        val file = byte.resolve("mapping-scheme-fixture.json")
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        Files.write(file, bytes)
        refused(byte)
        listOf("Mapping-scheme-fixture.json", "mapping-scheme-fixture.json.bak").forEach { alias ->
            val renamed = tree.copy()
            Files.move(renamed.resolve("mapping-scheme-fixture.json"), renamed.resolve(alias))
            refused(renamed)
            tree.rehash(renamed)
            refused(renamed)
        }
    }

    @Test
    fun `INV-AC-9790 a scheme fixture without schemeTag, with INT-SR added or in the wrong format refuses documents`() {
        val noTag = tree.copy()
        tree.edit(noTag, "mapping-scheme-fixture.json") { it.replace("\"schemeTag\": \"SWS\",", "") }
        refused(noTag)
        val twice = tree.copy()
        tree.edit(twice, "mapping-scheme-fixture.json") {
            it.replace(
                "\"entries\": [",
                "\"entries\": [{\"entryId\": \"MAP-SR\", \"internalTaxCode\": \"INT-SR\", \"irasCode\": \"SR\"," +
                    " \"schemeTag\": \"SWS\", \"sourceClass\": \"INFERRED\", \"sourceReference\": \"x\"},",
            )
        }
        refused(twice)
        val notFormat = tree.copy()
        tree.edit(notFormat, "mapping-scheme-fixture.json") { "{}" }
        refused(notFormat)
        val notJson = tree.copy()
        tree.edit(notJson, "mapping-scheme-fixture.json") { "[" }
        refused(notJson)
    }

    @Test
    fun `INV-AC-9790 the new document without inputs election or with INT-SR no longer gives ZR`() {
        val noElection = tree.copy()
        tree.edit(noElection, "$docName.json") { it.replace("\"election\": \"SWS\",", "") }
        runCatching { classified(noElection) }.getOrNull() shouldNotBe "ZR"
        val plain = tree.copy()
        tree.edit(plain, "$docName.json") { it.replace("INT-SWS-SR", "INT-SR") }
        runCatching { classified(plain) }.getOrNull() shouldNotBe "ZR"
    }
}
