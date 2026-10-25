package com.dynokore.gst.controlPlane.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * The golden-document corpus of INV-REQ-2660 (INV-Q-9781 A), criterion INV-AC-9790 (manifest part): one JSON file per
 * document, the mapping-fixture.json, and a manifest.json that lists each file name with its SHA-256, in sorted order.
 * Names and hashes are compared by equality. Every probe edits a copy of the committed tree and must fail the check.
 */
class CorpusManifestTest {
    private val json = JsonMapper.builder().build()

    @TempDir
    lateinit var tmp: Path

    private fun corpusDir(): Path =
        Paths.get("src/main/resources/conformance-corpus").also { check(Files.isDirectory(it)) { "missing $it" } }

    private fun copyCorpus(): Path {
        val target = Files.createDirectory(tmp.resolve("corpus"))
        Files.list(corpusDir()).use { it.forEach { f -> Files.copy(f, target.resolve(f.fileName.toString())) } }
        return target
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    private fun items(node: JsonNode): List<JsonNode> = (0 until node.size()).map { node.get(it) }

    private fun keys(node: JsonNode): List<String> = node.propertyNames().toList()

    private fun read(file: Path): JsonNode = json.readTree(Files.readAllBytes(file))

    private fun entries(dir: Path): List<Pair<String, String>> {
        val manifest = dir.resolve(MANIFEST)
        check(Files.isRegularFile(manifest)) { "no $MANIFEST in $dir" }
        return items(read(manifest).get("files")).map { it.get("name").asString() to it.get("sha256").asString() }
    }

    private fun fileNames(dir: Path): Set<String> =
        Files.list(dir).use { s ->
            s
                .map {
                    it.fileName.toString()
                }.toList()
                .toSet()
        }

    private fun field(
        doc: JsonNode,
        name: String,
    ): String {
        val v = doc.get(name)
        check(v != null && v.isString) { "missing or non-string $name" }
        return v.asString()
    }

    /** Names by equality, each hash by equality, entries in sorted order, then every golden document and the fixture. */
    private fun verify(dir: Path) {
        val entries = entries(dir)
        val names = entries.map { it.first }
        check(names.toSet().size == names.size) { "duplicate manifest entries" }
        check(names == names.sorted()) { "manifest entries are not in sorted file-name order" }
        check(MANIFEST !in names) { "$MANIFEST lists itself" }
        val onDisk = fileNames(dir) - MANIFEST
        check(onDisk == names.toSet()) { "files $onDisk differ from entries ${names.toSet()}" }
        entries.forEach { (name, hash) -> check(sha256(dir.resolve(name)) == hash) { "SHA-256 of $name differs" } }
        check(FIXTURE in names) { "$FIXTURE is not listed in $MANIFEST" }
        val fixture = read(dir.resolve(FIXTURE))
        check(fixture.isObject) { "$FIXTURE is not an object" }
        keys(fixture).forEach { key ->
            val value = fixture.get(key)
            check(
                value.isString && value.asString() in CATEGORY_CODES,
            ) { "fixture value of $key is not a code of INV-BR-1470" }
        }
        val sections = mutableSetOf<String>()
        val entityRefs = mutableSetOf<String>()
        names.filter { it != FIXTURE }.forEach { name ->
            sections +=
                verifyDocument(name, read(dir.resolve(name)), fixture, entityRefs)
        }
        check(entityRefs == setOf(ENTITY)) { "entityRef values are $entityRefs, not the one corpus entity" }
        check(sections == SECTIONS) { "sections are $sections" }
    }

    private fun verifyDocument(
        name: String,
        doc: JsonNode,
        fixture: JsonNode,
        entityRefs: MutableSet<String>,
    ): String {
        check(field(doc, "id") + ".json" == name) { "$name: id differs from the file name" }
        val section = field(doc, "section")
        check(section in SECTIONS) { "$name: section $section" }
        check(field(doc, "source").isNotBlank()) { "$name: blank source" }
        check(field(doc, "description").isNotBlank() && field(doc, "period").isNotBlank()) {
            "$name: blank description or period"
        }
        val expectedField = field(doc, "expectedField")
        val expected = field(doc, "expected")
        val valueSet = checkNotNull(VALUE_SETS[expectedField]) { "$name: expectedField $expectedField" }
        check(expected in valueSet) { "$name: expected $expected is not a $expectedField value" }
        val inputs = doc.get("inputs")
        check(inputs != null && inputs.isObject) { "$name: inputs is not an object" }
        keys(inputs).forEach {
            check(it in REQUEST_PROPERTIES) { "$name: inputs key $it is not a property of EvaluateDocumentRequest" }
        }
        entityRefs += field(inputs, "entityRef")
        field(inputs, "transactionDate")
        val internalTaxCode = field(inputs, "internalTaxCode")
        val mapped = fixture.get(internalTaxCode)
        check(mapped != null) { "$name: internalTaxCode $internalTaxCode has no fixture entry" }
        if (expectedField == "categoryCode") {
            check(
                mapped.asString() == expected,
            ) { "$name: expected $expected differs from the fixture value ${mapped.asString()}" }
        }
        return section
    }

    /** Edits one file, then rewrites manifest.json so the hashes match: the probe tests the content check, not the hash. */
    private fun mutateDoc(
        dir: Path,
        name: String,
        edit: (String) -> String,
    ) {
        val f = dir.resolve(name)
        val before = Files.readString(f)
        val after = edit(before)
        check(after != before) { "the probe edit changed nothing in $name" }
        Files.writeString(f, after)
        rehash(dir)
    }

    private fun rehash(dir: Path) {
        val names = (fileNames(dir) - MANIFEST).sorted()
        val files = names.map { mapOf("name" to it, "sha256" to sha256(dir.resolve(it))) }
        Files.writeString(dir.resolve(MANIFEST), json.writeValueAsString(mapOf("files" to files)))
    }

    private fun bad(dir: Path) {
        shouldThrow<IllegalStateException> { verify(dir) }
    }

    private fun docNamed(prefix: String): String = entries(corpusDir()).map { it.first }.first { it.startsWith(prefix) }

    @Test
    fun `INV-AC-9790 the committed corpus equals its manifest and every document is valid`() {
        verify(corpusDir())
    }

    @Test
    fun `every section has a document`() {
        val sections =
            entries(
                corpusDir(),
            ).map { it.first }.filter { it != FIXTURE }.map { field(read(corpusDir().resolve(it)), "section") }
        sections.toSet() shouldBe SECTIONS
    }

    @Test
    fun `one file added without an entry fails`() {
        val dir = copyCorpus()
        Files.writeString(dir.resolve("extra.json"), "{}")
        bad(dir)
    }

    @Test
    fun `one entry without a file fails`() {
        val dir = copyCorpus()
        Files.delete(dir.resolve(entries(dir).first().first))
        bad(dir)
    }

    @Test
    fun `one byte of a document changed fails`() {
        val dir = copyCorpus()
        val file = dir.resolve(entries(dir).first().first)
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        Files.write(file, bytes)
        bad(dir)
    }

    @Test
    fun `the look-alike name Manifest json in place of manifest json fails`() {
        val dir = copyCorpus()
        Files.move(dir.resolve(MANIFEST), dir.resolve("Manifest.json"))
        bad(dir)
    }

    @Test
    fun `two entries swapped fail`() {
        val dir = copyCorpus()
        val files = items(read(dir.resolve(MANIFEST)).get("files")).toMutableList()
        files[0] = files[1].also { files[1] = files[0] }
        Files.writeString(dir.resolve(MANIFEST), json.writeValueAsString(mapOf("files" to files)))
        bad(dir)
    }

    @Test
    fun `expectedField written scope_verdict fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, docNamed("sc-")) { it.replace("\"scopeVerdict\"", "\"scope_verdict\"") }
        bad(dir)
    }

    @Test
    fun `expected written IN-SCOPE fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, "ed-mixed-scope-rental-line.json") {
            it.replace("\"expected\": \"IN_SCOPE\"", "\"expected\": \"IN-SCOPE\"")
        }
        bad(dir)
    }

    @Test
    fun `expected written sr fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, "wx-r1-ex25-ee-supply.json") { it.replace("\"expected\": \"SR\"", "\"expected\": \"sr\"") }
        bad(dir)
    }

    @Test
    fun `expected 382 under documentTypeCode fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, "ed-cancellation-381.json") { it.replace("\"expected\": \"381\"", "\"expected\": \"382\"") }
        bad(dir)
    }

    @Test
    fun `expected SR under scopeVerdict fails`() {
        val dir = copyCorpus()
        mutateDoc(
            dir,
            "ed-mixed-scope-rental-line.json",
        ) { it.replace("\"expected\": \"IN_SCOPE\"", "\"expected\": \"SR\"") }
        bad(dir)
    }

    @Test
    fun `key side in inputs fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, "wx-r1-ex25-ee-supply.json") {
            it.replace("\"internalTaxCode\"", "\"side\": \"supply\",\n    \"internalTaxCode\"")
        }
        bad(dir)
    }

    @Test
    fun `internalTaxCode without a fixture entry fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, "wx-r1-ex25-ee-supply.json") { it.replace("\"INT-SR\"", "\"INT-UNMAPPED\"") }
        bad(dir)
    }

    @Test
    fun `a document whose entityRef differs fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, "wx-r1-ex25-ee-supply.json") { it.replace(ENTITY, "ENT-OTHER") }
        bad(dir)
    }

    @Test
    fun `a categoryCode document whose expected differs from the fixture value fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, "wx-r1-ex25-ee-supply.json") { it.replace("\"expected\": \"SR\"", "\"expected\": \"ZR\"") }
        bad(dir)
    }

    @Test
    fun `a fixture value that is not a code of INV-BR-1470 fails`() {
        val dir = copyCorpus()
        mutateDoc(dir, FIXTURE) { it.replace("\"INT-SR\": \"SR\"", "\"INT-SR\": \"SRX\"") }
        bad(dir)
    }

    @Test
    fun `Mapping-fixture json in place of mapping-fixture json fails`() {
        val dir = copyCorpus()
        Files.move(dir.resolve(FIXTURE), dir.resolve("Mapping-fixture.json"))
        rehash(dir)
        bad(dir)
    }

    @Test
    fun `a copy named mapping-fixture2 json is treated as a golden document and fails`() {
        val dir = copyCorpus()
        Files.copy(dir.resolve(FIXTURE), dir.resolve("mapping-fixture2.json"))
        rehash(dir)
        bad(dir)
    }

    @Test
    fun `mapping-fixture json present but not listed in the manifest fails`() {
        val dir = copyCorpus()
        val files = items(read(dir.resolve(MANIFEST)).get("files")).filter { it.get("name").asString() != FIXTURE }
        Files.writeString(dir.resolve(MANIFEST), json.writeValueAsString(mapOf("files" to files)))
        bad(dir)
    }

    private companion object {
        const val MANIFEST = "manifest.json"
        const val FIXTURE = "mapping-fixture.json"
        const val ENTITY = "ENT-CORPUS-1"

        val SECTIONS = setOf("WORKED_EXAMPLES", "SCENARIO_MATRIX", "SCOPE_MATRIX", "CODE_MATRIX", "EDGE_CORPUS")

        /** Property names of EvaluateDocumentRequest, platform-contracts origin/main 17808f9b140f67dad875f588daa6a6e77688235f. */
        val REQUEST_PROPERTIES =
            setOf(
                "entityRef",
                "transactionDate",
                "internalTaxCode",
                "election",
                "counterpartySchemeData",
                "documentFunction",
                "documentKind",
                "precedingDocumentNumber",
                "precedingDocumentDate",
                "lineSign",
                "apportionmentMode",
                "mixedUse",
                "direction",
            )

        /** The 35 codes of INV-BR-1470 (R1 Annex E). */
        val CATEGORY_CODES =
            setOf(
                "BL",
                "DS",
                "EP",
                "ES33",
                "ESN33",
                "IGDS",
                "IM",
                "IM-ESS",
                "IM-N33",
                "IM-RE",
                "ME",
                "NA",
                "NG",
                "NR",
                "OP",
                "OS",
                "SR",
                "SRCA-C",
                "SRCA-S",
                "SRLVG",
                "SROVR-LVG",
                "SROVR-RS",
                "SRRC",
                "TX",
                "TX-ESS",
                "TX-N33",
                "TX-RE",
                "TXCA",
                "TXNA",
                "TXRC-ESS",
                "TXRC-N33",
                "TXRC-RE",
                "TXRC-TS",
                "ZP",
                "ZR",
            )

        /** The value sets of EvaluationResult, rules/openapi.yaml of platform-contracts origin/main 17808f9b140f67dad875f588daa6a6e77688235f. */
        val VALUE_SETS =
            mapOf(
                "categoryCode" to CATEGORY_CODES,
                "scopeVerdict" to
                    setOf(
                        "SUBMITTABLE",
                        "NOT_SUBMITTABLE",
                        "PRE_OBLIGATION",
                        "IN_SCOPE",
                        "OUT_OF_SCOPE",
                        "EXCLUDED_TXN",
                    ),
                "documentTypeCode" to setOf("380", "381"),
            )
    }
}
