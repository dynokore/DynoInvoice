package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.IrasStatus
import com.dynokore.gst.payload.domain.MdeRow
import com.dynokore.gst.payload.domain.SubmissionType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** INV-T-7187 test helper: reads the values of a built document by the syntax path of each row (test source only). */
object FixtureValueMap {
    private const val SPECIFICATION = "PINT-SG-1.0"
    private val files = listOf("/mde/mde-pack-fixture.json", "/mde/mde-pack-credit-note-fixture.json")

    private fun fixtures(): List<JsonNode> =
        files.map { name ->
            JsonMapper().readTree(
                FixtureValueMap::class.java.getResourceAsStream(name) ?: error("$name is missing"),
            )
        }

    private fun str(node: JsonNode): String = if (node.isString) node.stringValue() else ""

    /** The `values` map of `MdeCompleteness.check`: the text of the first node a row selects; no node, no key. */
    fun read(
        xml: String,
        typeCode: String,
        rows: List<GateRow>,
    ): Map<String, String?> {
        val document = TableConformanceGate.parse(xml)
        val out = linkedMapOf<String, String?>()
        for (row in rows) {
            val nodes = TableConformanceGate.select(document, row.syntaxPath, typeCode)
            if (nodes.length > 0) out[row.businessTermId] = nodes.item(0).textContent
        }
        return out
    }

    /** The MDE rows of both fixture files; a missing `requiredInScenarios` is the empty set. */
    fun mdeRows(): List<MdeRow> =
        fixtures().flatMap { fixture ->
            fixture.path("rows").values().map { row ->
                MdeRow(
                    businessTermId = str(row.path("businessTermId")),
                    documentTypeCodes =
                        row
                            .path("documentTypeCodes")
                            .values()
                            .map { str(it) }
                            .toSet(),
                    submissionTypes =
                        row
                            .path(
                                "submissionTypes",
                            ).values()
                            .map { SubmissionType.valueOf(str(it)) }
                            .toSet(),
                    irasStatus = IrasStatus.valueOf(str(row.path("irasStatus"))),
                    requiredInScenarios =
                        row
                            .path("requiredInScenarios")
                            .values()
                            .map { str(it) }
                            .toSet(),
                )
            }
        }

    /** The rows of both fixture files that apply to [typeCode] and PINT-SG-1.0. */
    fun gateRows(typeCode: String): List<GateRow> =
        fixtures().flatMap { TableConformanceGate.applyingRows(it, typeCode, SPECIFICATION) }
}
