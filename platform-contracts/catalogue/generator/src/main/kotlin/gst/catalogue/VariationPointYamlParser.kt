package gst.catalogue

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Parses `variation-points.yaml` (the vendored copy in `catalogue/variation-points/`,
 * STK-61, CS-28) into [ParsedVariationPoint] rows. Each row is already close to its
 * final shape (it was written by the same extraction that reads Keel's `io_contract`
 * attribute, not authored free text like `error-codes.yaml`), so this parser mostly
 * validates and type-narrows rather than reducing free text to a fixed vocabulary.
 */
object VariationPointYamlParser {
    private val idPattern = Regex("^VP-\\d+$")

    fun parse(file: File): List<ParsedVariationPoint> {
        val yaml = Yaml()
        val root = yaml.load<Map<String, Any?>>(file.readText())
        @Suppress("UNCHECKED_CAST")
        val rows = root["variation_points"] as? List<Map<String, Any?>>
            ?: error("gst-catalogue: '$file' has no top-level 'variation_points' list.")
        return rows.map { parseRow(it, file) }
    }

    private fun parseRow(
        row: Map<String, Any?>,
        file: File,
    ): ParsedVariationPoint {
        val id = (row["id"] as? String)?.trim() ?: error("gst-catalogue: row without an 'id' in $file: $row")
        if (!idPattern.matches(id)) {
            error("gst-catalogue: '$id' in $file is not of the form VP-nn (INV-NOTE-3 §37.3).")
        }
        val requirement =
            (row["requirement"] as? String)?.trim() ?: error("gst-catalogue: $id has no 'requirement' in $file")
        val revision = (row["revision"] as? String)?.trim() ?: error("gst-catalogue: $id has no 'revision' in $file")
        val title = (row["title"] as? String)?.trim() ?: error("gst-catalogue: $id has no 'title' in $file")

        val classText =
            (row["class"] as? String)?.trim() ?: error("gst-catalogue: $id has no 'class' in $file")
        val vpClass =
            runCatching { VpClass.valueOf(classText) }.getOrElse {
                error(
                    "gst-catalogue: $id's class '$classText' is not one of ${VpClass.entries.joinToString()} " +
                        "(INV-REQ-1239 behaviour #1).",
                )
            }
        @Suppress("UNCHECKED_CAST")
        val classModifiers =
            (row["class_modifiers"] as? List<String> ?: emptyList()).map {
                runCatching { VpClassModifier.valueOf(it.uppercase()) }.getOrElse { _ ->
                    error("gst-catalogue: $id has an unrecognised class modifier '$it' in $file")
                }
            }

        @Suppress("UNCHECKED_CAST")
        val scope = (row["scope"] as? List<String>) ?: emptyList()
        val owner = (row["owner"] as? String)?.trim() ?: error("gst-catalogue: $id has no 'owner' in $file")
        @Suppress("UNCHECKED_CAST")
        val setBy = (row["set_by"] as? List<String>) ?: emptyList()
        val mustBeElected = row["must_be_elected"] as? Boolean ?: false
        val default = (row["default"] as? String)?.trim()
        if (!mustBeElected && default.isNullOrBlank()) {
            error("gst-catalogue: $id has no 'default' and is not must_be_elected in $file (INV-REQ-1239 behaviour #1).")
        }
        @Suppress("UNCHECKED_CAST")
        val options = (row["options"] as? List<String>) ?: emptyList()
        if (options.isEmpty()) {
            error("gst-catalogue: $id has no 'options' in $file (INV-REQ-1239 behaviour #1).")
        }
        val bounds = (row["bounds"] as? String)?.trim()

        val statusText = (row["status"] as? String)?.trim() ?: error("gst-catalogue: $id has no 'status' in $file")
        val status =
            runCatching { VpStatus.valueOf(statusText.uppercase()) }.getOrElse {
                error(
                    "gst-catalogue: $id's status '$statusText' is not one of ${VpStatus.entries.joinToString()} " +
                        "(INV-REQ-1251 behaviour #1).",
                )
            }
        val statusNote = (row["status_note"] as? String)?.trim()

        @Suppress("UNCHECKED_CAST")
        val governs = (row["governs"] as? List<String>) ?: emptyList()
        val formDependence = (row["form_dependence"] as? String)?.trim()

        return ParsedVariationPoint(
            id = id,
            requirement = requirement,
            revision = revision,
            title = title,
            vpClass = vpClass,
            classModifiers = classModifiers,
            scope = scope,
            owner = owner,
            setBy = setBy,
            mustBeElected = mustBeElected,
            default = default,
            options = options,
            bounds = bounds,
            status = status,
            statusNote = statusNote,
            governs = governs,
            formDependence = formDependence,
        )
    }
}
