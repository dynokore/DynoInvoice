package com.dynokore.platform.rulesevaluation.packs

import com.dynokore.platform.rulesevaluation.coding.MappingEntry
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Decodes `artefact.contents` of a MAPPING pack (`mapping-pack-contents.schema.json`, INV-T-11632) into the domain
 * entries. The members the schema names are checked as it states them; a property it does not name is ignored (API-41).
 * Uniqueness of `internalTaxCode` and `entryId` is the schema's own open point, checked at authoring and sign-off, and
 * is checked here as well because the coding reads one entry per internal code.
 */
object MappingPackContents {
    private const val PACK_TYPE = "MAPPING"
    private val SCHEME_TAG = Regex("^[A-Z][A-Z0-9_]{0,39}$")

    fun decode(contents: ByteArray): List<MappingEntry> {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val entries = root.array("entries", min = 1, max = 5000).map(::entry)
        entries.map { it.second }.duplicate()?.let { root.fail("entries", "names entryId '$it' twice") }
        entries.map { it.first.internalTaxCode }.duplicate()?.let {
            root.fail("entries", "maps internalTaxCode '$it' twice")
        }
        return entries.map { it.first }
    }

    private fun List<String>.duplicate(): String? = groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.key

    private fun entry(node: ContentsNode): Pair<MappingEntry, String> {
        val entryId = node.text("entryId", min = 1, max = 40)
        val internalTaxCode = node.text("internalTaxCode", min = 1, max = 40)
        val irasCode = node.text("irasCode", pattern = CODE_PATTERN)
        val sourceClass = node.text("sourceClass", oneOf = SOURCE_CLASSES)
        node.optionalText("sourceReference", min = 1)
        val schemeTag = node.optionalText("schemeTag", pattern = SCHEME_TAG)
        val accepted = node.optionalBoolean("accepted")
        if (node.has("acceptance")) acceptance(node.obj("acceptance"))
        if (accepted == true && sourceClass != "PRIMARY" && !node.has("acceptance")) {
            node.fail("acceptance", "is required when a $sourceClass entry is accepted")
        }
        return MappingEntry(internalTaxCode, irasCode, schemeTag) to entryId
    }

    private fun acceptance(node: ContentsNode) {
        node.text("rationale", min = 1, max = 500)
        val expires = node.text("expiresOn")
        try {
            LocalDate.parse(expires)
        } catch (_: DateTimeParseException) {
            node.fail("expiresOn", "is not a date: '$expires'")
        }
        node.text("acceptedBy", min = 1, max = 64)
    }
}
