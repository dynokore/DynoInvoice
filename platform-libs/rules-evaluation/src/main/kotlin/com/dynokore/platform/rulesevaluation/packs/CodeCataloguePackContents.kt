package com.dynokore.platform.rulesevaluation.packs

import com.dynokore.platform.rulesevaluation.coding.CatalogueEntry
import com.dynokore.platform.rulesevaluation.coding.CodeCatalogue
import com.dynokore.platform.rulesevaluation.pack.SourceClass

/**
 * Decodes `artefact.contents` of a CODE_CATALOGUE pack (`code-catalogue-pack-contents.schema.json`, INV-REQ-2429).
 * `complete` is carried and never read by a decision (INV-REQ-418 b3). The Annex E rate is kept as the text the pack
 * gives (`NA` or a decimal); the rate of a code is resolved from it by the document coding (INV-Q-11616).
 */
object CodeCataloguePackContents {
    private const val PACK_TYPE = "CODE_CATALOGUE"
    private val RATE = Regex("^(NA|[0-9]{1,3}(\\.[0-9]{1,4})?)$")
    private val SUBMISSION_RATE = Regex("^[0-9]{1,3}(\\.[0-9]{1,4})?$")
    private val SUPPLY_GROUPS = setOf("SUPPLY_STANDARD_RATED", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE")
    private val PURCHASE_GROUPS =
        setOf("PURCHASE_STANDARD_RATED", "PURCHASE_INPUT_TAX_ATTRIBUTION", "PURCHASE_ZERO_RATED_NON_REPORTABLE")

    fun decode(contents: ByteArray): CodeCatalogue {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val complete = root.optionalBoolean("complete") ?: root.fail("complete", "is required")
        val entries = root.array("codes", min = 1, max = 500).map(::entry)
        entries.map { it.code }.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let {
            root.fail("codes", "holds code '${it.key}' twice")
        }
        return CodeCatalogue(complete, entries)
    }

    private fun sideOf(group: String): String = if (group in SUPPLY_GROUPS) "SUPPLY" else "PURCHASE"

    private fun entry(node: ContentsNode): CatalogueEntry {
        val code = node.text("code", pattern = CODE_PATTERN)
        val group = node.text("group", oneOf = SUPPLY_GROUPS + PURCHASE_GROUPS)
        val side = node.text("side", oneOf = setOf("SUPPLY", "PURCHASE"))
        if (side != sideOf(group)) {
            node.fail("side", "is $side but group $group is of the other side")
        }
        val annexERate = node.text("annexERate", pattern = RATE)
        val submissionRate = node.optionalText("submissionRate", pattern = SUBMISSION_RATE)
        val sourceClass = node.text("sourceClass", oneOf = SOURCE_CLASSES)
        val sourceReference = node.text("sourceReference", min = 1)
        return CatalogueEntry(
            code,
            group,
            side,
            annexERate,
            submissionRate,
            SourceClass.valueOf(sourceClass),
            sourceReference,
        )
    }
}
