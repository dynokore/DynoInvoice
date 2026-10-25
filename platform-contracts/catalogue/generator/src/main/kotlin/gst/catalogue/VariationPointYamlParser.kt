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

    /**
     * Parses [file] and attaches each row's pending marker from [pendingFile]
     * (pending-fields.yaml, INV-T-3000) when given. A marker for an unknown row fails.
     */
    fun parse(
        file: File,
        pendingFile: File?,
    ): List<ParsedVariationPoint> {
        val rows = parse(file)
        val pending = pendingFile?.let { PendingFieldsParser.parse(it) } ?: return rows
        val unknown = pending.keys - rows.map { it.id }.toSet()
        if (unknown.isNotEmpty()) {
            error("gst-catalogue: $pendingFile names rows that are not in $file: ${unknown.sorted().joinToString()}")
        }
        return rows.map { it.copy(pendingQuestions = pending[it.id] ?: emptyList()) }
    }

    /**
     * The permission keys of `role-permissions.yaml`, read from the sibling directory `../role-permissions/` of
     * [file]'s directory (the layout of catalogue/); a missing file fails, so no row is ever checked against nothing.
     */
    fun siblingPermissionKeys(file: File): Set<String> {
        val table = File(file.absoluteFile.parentFile.parentFile, "role-permissions/role-permissions.yaml")
        check(table.isFile) { "gst-catalogue: ${table.path} (the permission keys of set_permission, INV-T-10133) does not exist." }
        return RolePermissionYamlParser.parse(table).permissions.map { it.key }.toSet()
    }

    /** Parses [file]; every row's `set_permission` must be its own `vp-NN.set` and a key of [permissionKeys]. */
    fun parse(
        file: File,
        permissionKeys: Set<String> = siblingPermissionKeys(file),
    ): List<ParsedVariationPoint> {
        val yaml = Yaml()
        val root = yaml.load<Map<String, Any?>>(file.readText())
        @Suppress("UNCHECKED_CAST") // SnakeYAML yields erased List/Map; the elvis below rejects a non-list, parseRow reads each row field by field
        val rows = root["variation_points"] as? List<Map<String, Any?>>
            ?: error("gst-catalogue: '$file' has no top-level 'variation_points' list.")
        return rows.map { parseRow(it, file, permissionKeys) }
    }

    private fun parseRow(
        row: Map<String, Any?>,
        file: File,
        permissionKeys: Set<String>,
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
        @Suppress("UNCHECKED_CAST") // SnakeYAML yields erased generics; element types come from the vendored YAML, whose rows the generator validates field by field
        val classModifiers =
            (row["class_modifiers"] as? List<String> ?: emptyList()).map {
                runCatching { VpClassModifier.valueOf(it.uppercase()) }.getOrElse { _ ->
                    error("gst-catalogue: $id has an unrecognised class modifier '$it' in $file")
                }
            }

        @Suppress("UNCHECKED_CAST") // SnakeYAML yields erased generics; element types come from the vendored YAML, whose rows the generator validates field by field
        val scope = (row["scope"] as? List<String>) ?: emptyList()
        val owner = (row["owner"] as? String)?.trim() ?: error("gst-catalogue: $id has no 'owner' in $file")
        val setPermission = row["set_permission"] as? String
        if (setPermission.isNullOrEmpty()) {
            error("gst-catalogue: $id has no 'set_permission' in $file (INV-T-10133, INV-Q-10127 A).")
        }
        if (setPermission !in permissionKeys) {
            error("gst-catalogue: $id's set_permission '$setPermission' is not a key of role-permissions.yaml in $file (INV-T-10133).")
        }
        if (setPermission != id.lowercase() + ".set") {
            error("gst-catalogue: $id's set_permission '$setPermission' is not '${id.lowercase()}.set' in $file (INV-T-10133).")
        }
        val mustBeElected = row["must_be_elected"] as? Boolean ?: false
        val default = (row["default"] as? String)?.trim()
        if (!mustBeElected && default.isNullOrBlank()) {
            error("gst-catalogue: $id has no 'default' and is not must_be_elected in $file (INV-REQ-1239 behaviour #1).")
        }
        @Suppress("UNCHECKED_CAST") // SnakeYAML yields erased generics; element types come from the vendored YAML, whose rows the generator validates field by field
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

        @Suppress("UNCHECKED_CAST") // SnakeYAML yields erased generics; element types come from the vendored YAML, whose rows the generator validates field by field
        val governs = (row["governs"] as? List<String>) ?: emptyList()
        val formDependence = (row["form_dependence"] as? String)?.trim()
        @Suppress("UNCHECKED_CAST") // SnakeYAML yields erased Map; VariationPointFieldsParser rejects non-text keys and every wrongly typed value
        val fields = (row["catalogue"] as? Map<String, Any?>)?.let { VariationPointFieldsParser.parse(id, it, file) }

        return ParsedVariationPoint(
            id = id,
            requirement = requirement,
            revision = revision,
            title = title,
            vpClass = vpClass,
            classModifiers = classModifiers,
            scope = scope,
            owner = owner,
            setPermission = setPermission,
            mustBeElected = mustBeElected,
            default = default,
            options = options,
            bounds = bounds,
            status = status,
            statusNote = statusNote,
            governs = governs,
            formDependence = formDependence,
            fields = fields,
        )
    }
}
