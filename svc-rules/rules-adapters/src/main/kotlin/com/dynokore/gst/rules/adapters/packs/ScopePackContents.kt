package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.classification.ClassificationRow
import com.dynokore.gst.rules.domain.classification.DocumentFunction
import com.dynokore.gst.rules.domain.classification.SignOfTotal

/**
 * Decodes the `classificationTable` of a SCOPE pack (`scope-pack-contents.schema.json`, INV-REQ-658). The other
 * sections are read by other evaluations; a member the schema does not name is ignored (API-41). The row key is unique,
 * a NOT_A_BILL row has no type code and any other has 380 or 381, a cancellation row is an ADJUSTMENT of 381.
 */
object ScopePackContents {
    private const val PACK_TYPE = "SCOPE"
    private val FUNCTIONS = DocumentFunction.entries.map { it.name }.toSet()
    private val SIGNS = SignOfTotal.entries.map { it.name }.toSet()
    private val TYPE_CODE = Regex("^38[01]$")

    fun decode(contents: ByteArray): List<ClassificationRow> {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val rows = root.array("classificationTable", min = 1, max = 200).map(::row)
        rows.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.let {
            root.fail("classificationTable", "holds the key '${it.key}' twice")
        }
        return rows
    }

    private fun row(node: ContentsNode): ClassificationRow {
        val upstream = DocumentFunction.valueOf(node.text("upstreamFunction", oneOf = FUNCTIONS))
        val kind = node.text("upstreamKind", min = 1, max = 32)
        val sign = SignOfTotal.valueOf(node.text("signOfTotal", oneOf = SIGNS))
        val result = DocumentFunction.valueOf(node.text("documentFunction", oneOf = FUNCTIONS))
        val code = node.optionalText("documentTypeCode", pattern = TYPE_CODE)
        val normalised = node.optionalBoolean("signNormalised") ?: node.fail("signNormalised", "is required")
        node.text("sourceClass", oneOf = SOURCE_CLASSES)
        node.text("sourceReference", min = 1)
        val status = node.optionalText("ruleStatus", oneOf = setOf("CONFIRMED", "PROVISIONAL"))
        val reference = node.optionalText("provisionalReference", min = 1)
        val cancellation = node.optionalBoolean("cancellation") ?: false
        checkConsistent(node, result, code, status, reference, cancellation)
        return ClassificationRow(
            upstream,
            kind,
            sign,
            result,
            code,
            normalised,
            status == "PROVISIONAL",
            reference,
            cancellation,
        )
    }

    @Suppress("LongParameterList") // the members of one row that the schema relates to each other
    private fun checkConsistent(
        node: ContentsNode,
        result: DocumentFunction,
        code: String?,
        status: String?,
        reference: String?,
        cancellation: Boolean,
    ) {
        if (result == DocumentFunction.NOT_A_BILL && code != null) node.fail("documentTypeCode", "must be absent")
        if (result != DocumentFunction.NOT_A_BILL && code == null) node.fail("documentTypeCode", "is required")
        if (status == "PROVISIONAL" && reference == null) node.fail("provisionalReference", "is required")
        if (status != "PROVISIONAL" && reference != null) node.fail("provisionalReference", "must be absent")
        if (cancellation && (result != DocumentFunction.ADJUSTMENT || code != "381")) {
            node.fail("cancellation", "requires documentFunction ADJUSTMENT and documentTypeCode 381")
        }
    }
}
