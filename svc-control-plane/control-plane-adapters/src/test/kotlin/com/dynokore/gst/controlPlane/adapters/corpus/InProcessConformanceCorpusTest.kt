package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.platform.rulesevaluation.EvaluationRefusal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The in-process corpus run (INV-AC-9790, INV-REQ-2660): load and verify the tree, then classify by the evaluation of
 * rules-evaluation. Every probe edits a copy of the committed tree and must make a check fail.
 */
class InProcessConformanceCorpusTest {
    @TempDir
    lateinit var tmp: Path

    private val tree by lazy { CorpusTree(tmp) }
    private val fixtures = InProcessConformanceCorpus.FIXTURES

    private fun scopePack() = tree.text("scope-pack.json")

    private fun refused(dir: Path) {
        shouldThrow<IllegalStateException> { tree.corpus(dir).documents() }
    }

    @Test
    fun `INV-AC-9790 documents are the manifest entries other than the five fixtures in manifest order`() {
        val expected = tree.manifestNames().filter { it !in fixtures }
        fixtures.all { it in tree.manifestNames() } shouldBe true
        tree.corpus().documents().map { it.id.value + ".json" } shouldBe expected
        expected.size shouldBe tree.manifestNames().size - 5
    }

    @Test
    fun `INV-AC-9790 the self-check gives every golden document the value of its expectedField`() {
        val corpus = tree.corpus()
        val result = tree.classifyAll(corpus, tree.pack("SCOPE", scopePack()))
        corpus.documents().forEach { result[it.id.value] shouldBe it.expected }
        // a candidate of a type that is not evaluated changes nothing
        tree.classifyAll(corpus, tree.pack("CALENDAR", "{}")) shouldBe result
    }

    @Test
    fun `INV-AC-9790 the same pack evaluated twice gives equal classifications`() {
        val corpus = tree.corpus()
        val pack = tree.pack("SCOPE", scopePack())
        tree.classifyAll(corpus, pack) shouldBe tree.classifyAll(corpus, pack)
        tree.classifyAll(tree.corpus(), pack) shouldBe tree.classifyAll(corpus, pack)
    }

    @Test
    fun `INV-AC-9790 each expectedField loads and an expectedField other than the three is refused`() {
        val sc = "sc-deemed-supply.json"
        val cm = "cm-supply-sr.json"
        val ed = "ed-cancellation-381.json"
        tree.corpus().documents().size shouldNotBe 0
        listOf(
            Triple(sc, "\"scopeVerdict\"", "\"scope_verdict\""),
            Triple(sc, "\"scopeVerdict\"", "\"ScopeVerdict\""),
            Triple(sc, "\"scopeVerdict\"", "\"scopeVerdict \""),
            Triple(cm, "\"categoryCode\"", "\"category_code\""),
            Triple(cm, "\"categoryCode\"", "\"\""),
            Triple(ed, "\"documentTypeCode\"", "\"documentType\""),
            Triple(ed, "\"documentTypeCode\"", "\"documenttypecode\""),
        ).forEach { (name, from, to) ->
            val dir = tree.copy()
            tree.edit(dir, name) { it.replace(from, to) }
            refused(dir)
        }
    }

    @Test
    fun `INV-AC-9790 a manifest entry whose file is missing refuses documents, for a document and each fixture`() {
        (listOf("cm-supply-sr.json") + fixtures).forEach { name ->
            val dir = tree.copy()
            Files.delete(dir.resolve(name))
            refused(dir)
        }
    }

    @Test
    fun `INV-AC-9790 one byte changed refuses documents, for a document and each fixture`() {
        (listOf("cm-supply-sr.json") + fixtures).forEach { name ->
            val dir = tree.copy()
            val bytes = Files.readAllBytes(dir.resolve(name))
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            Files.write(dir.resolve(name), bytes)
            refused(dir)
        }
    }

    @Test
    fun `INV-AC-9790 a manifest hash that is edited refuses documents`() {
        val dir = tree.copy()
        val manifest = dir.resolve("manifest.json")
        val text = Files.readString(manifest)
        val hash = tree.sha256(dir.resolve("cm-purchase-bl.json"))
        Files.writeString(manifest, text.replace(hash, hash.replaceFirst(hash[0], if (hash[0] == '0') '1' else '0')))
        refused(dir)
    }

    @Test
    fun `INV-AC-9790 a missing or unparseable manifest and an empty corpus refuse documents`() {
        val missing = tree.copy()
        Files.delete(missing.resolve("manifest.json"))
        refused(missing)
        val broken = tree.copy()
        Files.writeString(broken.resolve("manifest.json"), "{\"files\": [")
        refused(broken)
        val empty = tree.copy()
        Files
            .list(empty)
            .use { s -> s.map { it.fileName.toString() }.toList() }
            .filter { it !in fixtures && it != "manifest.json" }
            .forEach { Files.delete(empty.resolve(it)) }
        tree.rehash(empty)
        refused(empty)
    }

    @Test
    fun `INV-AC-9790 a fixture that is not listed in the manifest refuses documents`() {
        fixtures.forEach { name ->
            val dir = tree.copy()
            val files = tree.json.readTree(Files.readAllBytes(dir.resolve("manifest.json"))).get("files")
            val kept = (0 until files.size()).map { files.get(it) }.filter { it.get("name").asString() != name }
            Files.writeString(dir.resolve("manifest.json"), tree.json.writeValueAsString(mapOf("files" to kept)))
            refused(dir)
        }
    }

    @Test
    fun `INV-AC-9790 the look-alike names of a fixture alone are treated as absent`() {
        listOf(
            "mapping-fixture.json" to "Mapping-fixture.json",
            "profile-fixture.json" to "Profile-fixture.json",
            "scope-pack.json" to "Scope-pack.json",
            "scope-pack.json" to "scope-pack.json.bak",
            "scenario-pack.json" to "Scenario-pack.json",
            "code-catalogue-pack.json" to "Code-catalogue-pack.json",
        ).forEach { (name, alias) ->
            val renamedOnly = tree.copy()
            Files.move(renamedOnly.resolve(name), renamedOnly.resolve(alias))
            refused(renamedOnly)
            val renamedAndListed = tree.copy()
            Files.move(renamedAndListed.resolve(name), renamedAndListed.resolve(alias))
            tree.rehash(renamedAndListed)
            refused(renamedAndListed)
        }
    }

    @Test
    fun `INV-AC-9790 a baseline that is not valid JSON or not in its schema format refuses documents`() {
        listOf("code-catalogue-pack.json", "scenario-pack.json", "scope-pack.json").forEach { name ->
            val notJson = tree.copy()
            tree.edit(notJson, name) { "{ not json" }
            refused(notJson)
            val notFormat = tree.copy()
            tree.edit(notFormat, name) { "{}" }
            refused(notFormat)
            val trailing = tree.copy()
            tree.edit(trailing, name) { it + "{}" }
            refused(trailing)
        }
    }

    @Test
    fun `INV-AC-9790 a mapping or profile fixture that does not parse refuses documents`() {
        listOf("mapping-fixture.json", "profile-fixture.json").forEach { name ->
            val dir = tree.copy()
            tree.edit(dir, name) { "[" }
            refused(dir)
        }
        val noKey = tree.copy()
        tree.edit(noKey, "profile-fixture.json") { it.replace("\"partiallyExempt\": false,", "") }
        refused(noKey)
        val defaulted = tree.copy()
        tree.edit(defaulted, "profile-fixture.json") {
            it.replace("\"obligationStartDate\": \"2026-02-02\"", "\"obligationStartDate\": null")
        }
        refused(defaulted)
        val elected = tree.copy()
        tree.edit(elected, "profile-fixture.json") { it.replace("\"election\": null", "\"election\": {}") }
        refused(elected)
    }

    @Test
    fun `INV-AC-9790 a document that does not parse or has an unknown inputs member refuses documents`() {
        val unparsable = tree.copy()
        tree.edit(unparsable, "cm-supply-sr.json") { "{" }
        refused(unparsable)
        val unknown = tree.copy()
        tree.edit(unknown, "cm-supply-sr.json") {
            it.replace("\"internalTaxCode\"", "\"side\": \"supply\",\n    \"internalTaxCode\"")
        }
        refused(unknown)
        val duplicate = tree.copy()
        tree.edit(duplicate, "cm-supply-sr.json") {
            it.replace("\"direction\": \"SALE\"", "\"direction\": \"SALE\", \"direction\": \"PURCHASE\"")
        }
        refused(duplicate)
    }

    @Test
    fun `INV-AC-9790 a candidate SCOPE pack that changes one scope verdict changes a scope golden document`() {
        val corpus = tree.corpus()
        val base = tree.classifyAll(corpus, tree.pack("SCOPE", scopePack()))
        val changed =
            scopePack().replace(
                Regex("(\"categoryCode\": \"EP\",\\s*\"lineVerdict\": )\"OUT_OF_SCOPE\""),
                "$1\"IN_SCOPE\"",
            )
        changed shouldNotBe scopePack()
        val result = tree.classifyAll(corpus, tree.pack("SCOPE", changed))
        base["sc-exempt-purchase"] shouldBe "OUT_OF_SCOPE"
        result["sc-exempt-purchase"] shouldBe "IN_SCOPE"
        result.filterKeys { it != "sc-exempt-purchase" } shouldBe base.filterKeys { it != "sc-exempt-purchase" }
    }

    @Test
    fun `INV-AC-9790 a candidate CODE_CATALOGUE pack without a code the corpus maps is refused by the evaluation`() {
        val corpus = tree.corpus()
        val catalogue = tree.json.readTree(tree.text("code-catalogue-pack.json"))
        val kept =
            (0 until catalogue.get("codes").size()).map { catalogue.get("codes").get(it) }.filter {
                it.get("code").asString() !=
                    "SR"
            }
        val candidate = tree.json.writeValueAsString(mapOf("complete" to true, "codes" to kept))
        val sr = corpus.documents().first { it.id.value == "cm-supply-sr" }
        shouldThrow<EvaluationRefusal> { corpus.classify(tree.pack("CODE_CATALOGUE", candidate), sr) }
        val tx = corpus.documents().first { it.id.value == "cm-purchase-tx" }
        corpus.classify(tree.pack("CODE_CATALOGUE", candidate), tx) shouldBe "TX"
    }

    @Test
    fun `INV-AC-9790 a candidate SCENARIO pack that is not in its format is refused by the evaluation`() {
        val corpus = tree.corpus()
        val doc = corpus.documents().first()
        shouldThrow<RuntimeException> { corpus.classify(tree.pack("SCENARIO", "{}"), doc) }
        corpus.classify(tree.pack("SCENARIO", tree.text("scenario-pack.json")), doc) shouldBe doc.expected
    }

    @Test
    fun `INV-AC-9790 a CALENDAR candidate and the look-alike types scope give the baseline-only result`() {
        val corpus = tree.corpus()
        val baselineOnly = tree.classifyAll(corpus, tree.pack("CALENDAR", "{}"))
        val breaking = scopePack().replace("\"categoryCode\": \"EP\"", "\"categoryCode\": \"EPX\"")
        tree.classifyAll(corpus, tree.pack("scope", breaking)) shouldBe baselineOnly
        tree.classifyAll(corpus, tree.pack("Scope", breaking)) shouldBe baselineOnly
        shouldThrow<IllegalArgumentException> { tree.pack("SCOPE ", breaking) }
    }

    @Test
    fun `INV-AC-9790 a candidate of a listed type with null contents is refused, never replaced by its baseline`() {
        val corpus = tree.corpus()
        val doc = corpus.documents().first()
        listOf("CODE_CATALOGUE", "SCENARIO", "SCOPE").forEach {
            shouldThrow<IllegalStateException> { corpus.classify(tree.pack(it, null), doc) }
        }
    }

    @Test
    fun `INV-AC-9790 a document that is not a golden document of the corpus is refused`() {
        val corpus = tree.corpus()
        val other =
            corpus.documents().first().copy(
                id =
                    com.dynokore.gst.controlPlane.domain.release
                        .DocumentId("not-golden"),
            )
        shouldThrow<IllegalArgumentException> { corpus.classify(tree.pack("SCOPE", scopePack()), other) }
    }
}
