package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.pack.BoxBasis
import com.dynokore.gst.rules.domain.pack.ReturnBox
import com.dynokore.gst.rules.domain.pack.ReturnMappingEntry
import com.dynokore.gst.rules.domain.pack.SourceClass

/**
 * Decodes `entries` of a RETURN_MAPPING pack (`return-mapping-pack-contents.schema.json`, INV-REQ-654). An entry
 * without `sourceClass` or `sourceReference`, a `direction` outside SALE and PURCHASE, a repeated pair
 * (`categoryCode`, `direction`) or a Box repeated within one entry is refused naming the entry (INV-AC-7968). The class
 * is read as written, never upgraded (INV-AC-7970). A member the schema does not name is ignored (API-41).
 */
object ReturnMappingPackContents {
    private const val PACK_TYPE = "RETURN_MAPPING"
    private val DIRECTIONS = Direction.entries.map { it.name }.toSet()
    private val BASES = BoxBasis.entries.map { it.name }.toSet()

    fun decode(contents: ByteArray): List<ReturnMappingEntry> {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val entries = root.array("entries", min = 1, max = 5000).map(::entry)
        entries.groupBy { it.categoryCode to it.direction }.entries.firstOrNull { it.value.size > 1 }?.let {
            root.fail("entries", "maps '${it.key.first}/${it.key.second}' twice")
        }
        return entries
    }

    private fun entry(node: ContentsNode): ReturnMappingEntry {
        val code = node.text("categoryCode", pattern = CODE_PATTERN)
        val direction = Direction.valueOf(node.text("direction", oneOf = DIRECTIONS))
        val boxes = node.array("boxes", min = 1, max = 20).map(::box)
        boxes.groupBy { it.box }.entries.firstOrNull { it.value.size > 1 }?.let {
            node.fail("boxes", "holds Box '${it.key}' twice")
        }
        val sourceClass = SourceClass.valueOf(node.text("sourceClass", oneOf = SOURCE_CLASSES))
        val reference = node.text("sourceReference", min = 1)
        return ReturnMappingEntry(code, direction, boxes, sourceClass, reference)
    }

    private fun box(node: ContentsNode): ReturnBox =
        ReturnBox(node.text("box", min = 1, max = 10), BoxBasis.valueOf(node.text("basis", oneOf = BASES)))
}
