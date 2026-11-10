package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.gst.controlPlane.domain.release.CorpusSection
import com.dynokore.gst.controlPlane.domain.release.DocumentId
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import com.dynokore.platform.rulesevaluation.CounterpartySchemeData
import com.dynokore.platform.rulesevaluation.EvaluateRequest
import com.dynokore.platform.rulesevaluation.EvaluationPackTypes
import com.dynokore.platform.rulesevaluation.EvaluationProfile
import com.dynokore.platform.rulesevaluation.coding.MappingEntry
import com.dynokore.platform.rulesevaluation.coding.TenantMapping
import com.dynokore.platform.rulesevaluation.packs.CodeCataloguePackContents
import com.dynokore.platform.rulesevaluation.packs.PackContentsFailure
import com.dynokore.platform.rulesevaluation.packs.ScenarioPackContents
import com.dynokore.platform.rulesevaluation.packs.ScopePackContents
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** A golden document with the member of the evaluation result its classification is read from, and its request. */
internal class LoadedDocument(
    val golden: GoldenDocument,
    val field: String,
    val request: EvaluateRequest,
)

/** The verified content of a corpus tree: documents, the three baselines, the tenant mapping and the profile. */
internal class LoadedCorpus(
    val documents: List<LoadedDocument>,
    val baselines: Map<String, String>,
    val mapping: TenantMapping,
    val profile: EvaluationProfile,
)

/**
 * Reads and verifies a corpus tree (NIST SP 800-53 SI-7, SI-10): every manifest entry is present and equals its
 * SHA-256, every file parses and is in its format, the five fixtures are listed, and the corpus is not empty. Any
 * failure throws; nothing is defaulted.
 */
internal class CorpusLoader(
    private val source: CorpusSource,
) {
    fun load(): LoadedCorpus {
        val manifest = parse(MANIFEST, checkNotNull(source.read(MANIFEST)) { "$MANIFEST is missing" })
        val entries = manifestEntries(manifest)
        val bytes = entries.associate { (name, hash) -> name to verified(name, hash) }
        FIXTURES.forEach { check(it in bytes) { "$it is not listed in $MANIFEST" } }
        val mapping = CorpusFixtures.mappingOf(parse(MAPPING_FIXTURE, bytes.getValue(MAPPING_FIXTURE)))
        val profile = CorpusFixtures.profileOf(parse(PROFILE_FIXTURE, bytes.getValue(PROFILE_FIXTURE)))
        val baselines = BASELINE_FILES.mapValues { (_, name) -> baselineOf(name, bytes.getValue(name)) }
        val documents =
            entries.map { it.first }.filter { it !in FIXTURES }.map { documentOf(it, parse(it, bytes.getValue(it))) }
        check(documents.isNotEmpty()) { "the corpus holds no golden document" }
        check(documents.map { it.golden.id }.toSet().size == documents.size) { "a document id occurs twice" }
        return LoadedCorpus(documents, baselines, mapping, profile)
    }

    private fun manifestEntries(manifest: JsonNode): List<Pair<String, String>> {
        val files = manifest.get("files")
        check(files != null && files.isArray && files.size() > 0) { "$MANIFEST has no files list" }
        val entries =
            (0 until files.size()).map {
                jsonText(files.get(it), "name", MANIFEST) to jsonText(files.get(it), "sha256", MANIFEST)
            }
        check(entries.map { it.first }.toSet().size == entries.size) { "$MANIFEST lists a file twice" }
        entries.forEach {
            check(
                SAFE_NAME.matches(it.first),
            ) { "$MANIFEST lists the name ${it.first}, which is not a plain file name" }
        }
        check(MANIFEST !in entries.map { it.first }) { "$MANIFEST lists itself" }
        return entries
    }

    private fun verified(
        name: String,
        hash: String,
    ): ByteArray {
        val content = checkNotNull(source.read(name)) { "$name is listed in $MANIFEST but missing" }
        check(sha256(content) == hash) { "SHA-256 of $name differs from $MANIFEST" }
        return content
    }

    private fun parse(
        name: String,
        content: ByteArray,
    ): JsonNode =
        try {
            JSON.readTree(content)
        } catch (e: tools.jackson.core.JacksonException) {
            throw IllegalStateException("$name does not parse", e)
        }

    /** A baseline is valid JSON and decodes in the format of its pack type; the decoders throw otherwise. */
    private fun baselineOf(
        name: String,
        content: ByteArray,
    ): String {
        parse(name, content)
        try {
            when (name) {
                BASELINE_FILES.getValue(EvaluationPackTypes.CODE_CATALOGUE) -> {
                    CodeCataloguePackContents.decode(content)
                }

                BASELINE_FILES.getValue(EvaluationPackTypes.SCENARIO) -> {
                    ScenarioPackContents.decode(content)
                }

                else -> {
                    ScopePackContents.decodeClassificationTable(content)
                    ScopePackContents.decodeScopeData(content)
                }
            }
        } catch (e: PackContentsFailure) {
            throw IllegalStateException("$name is not in the format of its pack type", e)
        }
        return String(content, Charsets.UTF_8)
    }

    private fun documentOf(
        name: String,
        doc: JsonNode,
    ): LoadedDocument {
        check(doc.isObject) { "$name is not an object" }
        val id = jsonText(doc, "id", name)
        check("$id.json" == name) { "$name: id $id differs from the file name" }
        val field = jsonText(doc, "expectedField", name)
        check(field in EXPECTED_FIELDS) { "$name: expectedField $field is not one of $EXPECTED_FIELDS" }
        val section =
            try {
                CorpusSection.valueOf(jsonText(doc, "section", name))
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("$name: section is not a corpus section", e)
            }
        val inputs = doc.get("inputs")
        check(inputs != null && inputs.isObject) { "$name: inputs is not an object" }
        return LoadedDocument(
            GoldenDocument(DocumentId(id), section, jsonText(doc, "expected", name)),
            field,
            requestOf(name, inputs),
        )
    }

    private fun requestOf(
        name: String,
        inputs: JsonNode,
    ): EvaluateRequest {
        inputs.propertyNames().forEach {
            check(it in REQUEST_MEMBERS) { "$name: inputs member $it is not a property of EvaluateDocumentRequest" }
        }

        fun optional(member: String): String? = inputs.get(member)?.let { jsonText(inputs, member, name) }

        fun day(member: String): LocalDate? =
            optional(member)?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: DateTimeParseException) {
                    throw IllegalStateException("$name: $member is not an ISO date", e)
                }
            }
        return EvaluateRequest(
            transactionDate = checkNotNull(day("transactionDate")) { "$name: transactionDate is missing" },
            internalTaxCode = checkNotNull(optional("internalTaxCode")) { "$name: internalTaxCode is missing" },
            precedingDocumentDate = day("precedingDocumentDate"),
            election = optional("election"),
            counterpartySchemeData = inputs.get("counterpartySchemeData")?.let { schemeData(name, it) },
            documentFunction = optional("documentFunction"),
            documentKind = optional("documentKind"),
            lineSign = optional("lineSign"),
            apportionmentMode = optional("apportionmentMode"),
            mixedUse = optional("mixedUse"),
            direction = optional("direction"),
        )
    }

    private fun schemeData(
        name: String,
        node: JsonNode,
    ): CounterpartySchemeData {
        check(node.isObject) { "$name: counterpartySchemeData is not an object" }
        val schemes = node.get("approvedSchemes")
        val agreed = node.get("arcsGstChargeAgreed")
        check(agreed == null || agreed.isBoolean) { "$name: arcsGstChargeAgreed is not a boolean" }
        check(schemes == null || (schemes.isArray && (0 until schemes.size()).all { schemes.get(it).isString })) {
            "$name: approvedSchemes is not a list of strings"
        }
        return CounterpartySchemeData(
            schemes?.let { s -> (0 until s.size()).map { s.get(it).asString() } },
            agreed?.asBoolean(),
        )
    }

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    companion object {
        const val MANIFEST = "manifest.json"
        const val MAPPING_FIXTURE = "mapping-fixture.json"
        const val PROFILE_FIXTURE = "profile-fixture.json"

        /** The baseline contents file per evaluated pack type. */
        val BASELINE_FILES: Map<String, String> =
            mapOf(
                EvaluationPackTypes.CODE_CATALOGUE to "code-catalogue-pack.json",
                EvaluationPackTypes.SCENARIO to "scenario-pack.json",
                EvaluationPackTypes.SCOPE to "scope-pack.json",
            )

        /** The five fixture files of the tree; every other manifest entry is a golden document. */
        val FIXTURES: Set<String> = setOf(MAPPING_FIXTURE, PROFILE_FIXTURE) + BASELINE_FILES.values

        const val CATEGORY_CODE = "categoryCode"
        const val SCOPE_VERDICT = "scopeVerdict"
        private const val DOCUMENT_TYPE_CODE = "documentTypeCode"
        private val EXPECTED_FIELDS = setOf(CATEGORY_CODE, SCOPE_VERDICT, DOCUMENT_TYPE_CODE)
        private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.json$")

        /** The properties of EvaluateDocumentRequest (platform-contracts); two of them the evaluation does not read. */
        private val REQUEST_MEMBERS =
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

        private val JSON =
            JsonMapper
                .builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
    }
}
