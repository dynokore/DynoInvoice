package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.platform.rulesevaluation.EvaluationRefusal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The fixture feed (INV-Q-10094), the profile input and the golden-document input fix of ed-cancellation-381: what the
 * evaluation reads from mapping-fixture.json, profile-fixture.json and the document inputs decides the classification.
 */
class CorpusFixtureFeedTest {
    @TempDir
    lateinit var tmp: Path

    private val tree by lazy { CorpusTree(tmp) }

    private fun scopePack() = tree.text("scope-pack.json")

    private fun classify(
        dir: Path,
        id: String,
    ): String? {
        val corpus = tree.corpus(dir)
        return corpus.classify(tree.pack("SCOPE", scopePack()), corpus.documents().first { it.id.value == id })
    }

    @Test
    fun `INV-AC-9790 for a categoryCode document the classification equals the fixture value of its internalTaxCode`() {
        val fixture = tree.read("mapping-fixture.json")
        val corpus = tree.corpus()
        val pack = tree.pack("SCOPE", scopePack())
        val schemeCodes =
            tree.read("mapping-scheme-fixture.json").get("entries").let { e ->
                (0 until e.size()).map { e.get(it).get("internalTaxCode").asString() }.toSet()
            }
        var checked = 0
        tree.manifestNames().filter { it !in InProcessConformanceCorpus.FIXTURES }.forEach { name ->
            val doc = tree.read(name)
            val code = doc.get("inputs").get("internalTaxCode").asString()
            // a scheme-tagged code is mapped by mapping-scheme-fixture.json and decided by the scenario pack
            if (doc.get("expectedField").asString() == "categoryCode" && code !in schemeCodes) {
                val golden = corpus.documents().first { it.id.value == doc.get("id").asString() }
                val mapped = fixture.get(code).asString()
                corpus.classify(pack, golden) shouldBe mapped
                checked++
            }
        }
        (checked > 0) shouldBe true
    }

    @Test
    fun `INV-AC-9790 a fixture entry changed to another category code changes the classification`() {
        val dir = tree.copy()
        tree.edit(dir, "mapping-fixture.json") { it.replace("\"INT-SR\": \"SR\"", "\"INT-SR\": \"ZR\"") }
        classify(dir, "cm-supply-sr") shouldBe "ZR"
        classify(tree.committed, "cm-supply-sr") shouldBe "SR"
    }

    @Test
    fun `INV-AC-9790 the fixture entry removed makes classify throw and never returns a default code`() {
        val dir = tree.copy()
        tree.edit(dir, "mapping-fixture.json") { it.replace("  \"INT-SR\": \"SR\",\n", "") }
        shouldThrow<EvaluationRefusal> { classify(dir, "cm-supply-sr") }
    }

    @Test
    fun `INV-AC-9790 mapping-fixture json absent or not parseable refuses documents`() {
        val dir = tree.copy()
        java.nio.file.Files
            .delete(dir.resolve("mapping-fixture.json"))
        shouldThrow<IllegalStateException> { tree.corpus(dir).documents() }
        val bad = tree.copy()
        tree.edit(bad, "mapping-fixture.json") { "{" }
        shouldThrow<IllegalStateException> { tree.corpus(bad).documents() }
    }

    @Test
    fun `INV-AC-9790 an obligationStartDate after the corpus date changes the scope documents`() {
        val dir = tree.copy()
        tree.edit(dir, "profile-fixture.json") { it.replace("2026-02-02", "2026-10-02") }
        classify(tree.committed, "sc-deemed-supply") shouldBe "EXCLUDED_TXN"
        classify(dir, "sc-deemed-supply") shouldBe "PRE_OBLIGATION"
        classify(dir, "ed-mixed-scope-rental-line") shouldBe "PRE_OBLIGATION"
    }

    @Test
    fun `INV-AC-9790 the profile of the committed tree has an open window for 2026-10-01 and no election`() {
        val profile = tree.read("profile-fixture.json")
        (profile.get("obligationStartDate").asString() <= "2026-10-01") shouldBe true
        profile.get("registrationEndDate").isNull shouldBe true
        profile.get("voluntaryOverSubmission").asBoolean() shouldBe false
        profile.get("partiallyExempt").asBoolean() shouldBe false
        profile.get("election").isNull shouldBe true
    }

    @Test
    fun `INV-AC-9790 ed-cancellation-381 has inputs documentFunction ADJUSTMENT and expected 381`() {
        val doc = tree.read("ed-cancellation-381.json")
        doc.get("inputs").get("documentFunction").asString() shouldBe "ADJUSTMENT"
        doc.get("expected").asString() shouldBe "381"
        doc.get("expectedField").asString() shouldBe "documentTypeCode"
        classify(tree.committed, "ed-cancellation-381") shouldBe "381"
    }

    @Test
    fun `INV-AC-9790 the cancellation without documentFunction or with BILL_FOR_PAYMENT gives no 381`() {
        val removed = tree.copy()
        tree.edit(removed, "ed-cancellation-381.json") { it.replace("    \"documentFunction\": \"ADJUSTMENT\",\n", "") }
        classify(removed, "ed-cancellation-381") shouldBe null
        val other = tree.copy()
        tree.edit(other, "ed-cancellation-381.json") { it.replace("\"ADJUSTMENT\"", "\"BILL_FOR_PAYMENT\"") }
        shouldThrow<EvaluationRefusal> { classify(other, "ed-cancellation-381") }
    }

    @Test
    fun `INV-AC-9790 the cancellation expected changed to 380 differs from the evaluation`() {
        val dir = tree.copy()
        tree.edit(dir, "ed-cancellation-381.json") { it.replace("\"expected\": \"381\"", "\"expected\": \"380\"") }
        val corpus = tree.corpus(dir)
        val golden = corpus.documents().first { it.id.value == "ed-cancellation-381" }
        golden.expected shouldBe "380"
        corpus.classify(tree.pack("SCOPE", scopePack()), golden) shouldNotBe golden.expected
    }
}
