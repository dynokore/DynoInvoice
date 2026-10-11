package gst.catalogue

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.math.BigDecimal

/**
 * Parses `operating-profiles.yaml` (catalogue/operating-profiles/, vendored from the Keel
 * PRF rows, INV-T-3277) strictly: an unknown key or a wrongly typed field fails, so a new
 * Keel field needs a generator change rather than being dropped silently.
 */
object OperatingProfileYamlParser {
    private val idPattern = Regex("^PRF-\\d+$")
    private val vpPattern = Regex("^VP-\\d+$")
    private val questionPattern = Regex("^INV-Q-\\d+$")
    private val rowKeys = setOf("id", "requirement", "revision", "name", "title", "profile")
    private val profileKeys = setOf("version", "status", "note", "basis", "values")
    private val valueKeys =
        setOf(
            "requirement", "text", "option", "options", "typed", "typed_value", "condition", "recommended",
            "otherwise_option", "alternatives", "alternative_by", "adds", "at_apply", "explicit_default",
            "needs_acknowledgement", "untyped_reason",
        )
    private val typedKeys = setOf("type", "unit", "value")

    /** Parses [file]; an untyped value takes its question from [pendingFile] (pending-values.yaml). */
    fun parse(
        file: File,
        pendingFile: File,
    ): List<ParsedOperatingProfile> {
        val pending = parsePending(pendingFile)
        val rows = parseRaw(file, pending)
        val known = rows.flatMap { p -> p.values.map { p.id to it } }.associate { (id, v) -> (id to v.vp) to v }
        pending.keys.forEach { key ->
            val value = known[key] ?: error("gst-catalogue: $pendingFile names ${key.first} ${key.second}, which $file does not have.")
            if (value.unresolved == null) {
                error("gst-catalogue: $pendingFile marks ${key.first} ${key.second} pending, but Keel types it now: delete the stale marker.")
            }
        }
        return rows
    }

    /** Parses a snapshot without pending markers (the previous snapshot, compared value by value). */
    fun parseSnapshot(file: File): List<ParsedOperatingProfile> = parseRaw(file, null)

    private fun parseRaw(
        file: File,
        pending: Map<Pair<String, String>, String>?,
    ): List<ParsedOperatingProfile> {
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        val rows = root["operating_profiles"] as? List<*> ?: error("gst-catalogue: '$file' has no top-level 'operating_profiles' list.")
        val parsed = rows.map { parseRow(map(it, "row", file), file, pending) }
        val duplicates = parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) error("gst-catalogue: duplicate profile ids in $file: ${duplicates.joinToString()}")
        return parsed
    }

    private fun parseRow(
        row: Map<String, Any?>,
        file: File,
        pending: Map<Pair<String, String>, String>?,
    ): ParsedOperatingProfile {
        val id = text(row, "id", "row", file)
        if (!idPattern.matches(id)) error("gst-catalogue: '$id' in $file is not of the form PRF-n (INV-NOTE-3 §37.4).")
        unknownKeys(row, rowKeys, id, file)
        val profile = map(row["profile"], "$id profile", file)
        unknownKeys(profile, profileKeys, "$id profile", file)
        val version = profile["version"] as? Int ?: error("gst-catalogue: $id has no whole-number profile version in $file (INV-Q-3283).")
        if (version < 1) error("gst-catalogue: $id's version $version is not a positive whole number (INV-Q-3283).")
        val statusText = text(profile, "status", id, file)
        val status =
            ProfileStatus.entries.find { it.name == statusText.uppercase() }
                ?: error("gst-catalogue: $id's status '$statusText' is not one of ${ProfileStatus.entries.joinToString()}.")
        val values = map(profile["values"], "$id values", file)
        return ParsedOperatingProfile(
            id = id,
            requirement = text(row, "requirement", id, file),
            revision = text(row, "revision", id, file),
            name = text(row, "name", id, file),
            title = text(row, "title", id, file),
            version = version,
            status = status,
            note = optText(profile, "note", id, file),
            values =
                values.entries.map { (vp, v) ->
                    if (!vpPattern.matches(vp)) error("gst-catalogue: $id sets '$vp', which is not of the form VP-nn.")
                    parseValue(id, vp, map(v, "$id $vp", file), file, pending)
                },
        )
    }

    private fun parseValue(
        id: String,
        vp: String,
        v: Map<String, Any?>,
        file: File,
        pending: Map<Pair<String, String>, String>?,
    ): ParsedProfileValue {
        val where = "$id $vp"
        unknownKeys(v, valueKeys, where, file)
        val typed = v["typed"]?.let { it as? Boolean ?: error("gst-catalogue: $where 'typed' is not true/false in $file.") } ?: true
        val reason = optText(v, "untyped_reason", where, file)
        val unresolved =
            if (typed) {
                if (reason != null) error("gst-catalogue: $where has an untyped_reason but is not marked typed: false in $file.")
                null
            } else {
                if (reason == null) error("gst-catalogue: $where is typed: false with no untyped_reason in $file.")
                val question =
                    if (pending == null) {
                        ""
                    } else {
                        pending[id to vp]
                            ?: error(
                                "gst-catalogue: $where is untyped in Keel ($reason) and pending-values.yaml names no open " +
                                    "question for it: raise one and list it there; never invent an option.",
                            )
                    }
                UnresolvedValue(reason, question)
            }
        val value =
            ParsedProfileValue(
                vp = vp,
                requirement = text(v, "requirement", where, file),
                text = text(v, "text", where, file),
                option = optText(v, "option", where, file),
                options = list(v, "options", where, file),
                typedValue = v["typed_value"]?.let { parseTyped(map(it, "$where typed_value", file), where, file) },
                condition = optText(v, "condition", where, file),
                recommended = bool(v, "recommended", where, file),
                otherwiseOption = optText(v, "otherwise_option", where, file),
                alternatives = list(v, "alternatives", where, file),
                alternativeBy = optText(v, "alternative_by", where, file),
                adds = list(v, "adds", where, file),
                atApply = optText(v, "at_apply", where, file),
                explicitDefault = bool(v, "explicit_default", where, file),
                needsAcknowledgement = list(v, "needs_acknowledgement", where, file),
                unresolved = unresolved,
            )
        val selections = listOfNotNull(value.option).size + (if (value.options.isEmpty()) 0 else 1)
        if (unresolved == null && selections != 1) {
            error("gst-catalogue: $where must set exactly one of 'option' or 'options' in $file.")
        }
        if (unresolved != null && (selections != 0 || value.typedValue != null)) {
            error("gst-catalogue: $where is untyped but carries an option or typed value in $file.")
        }
        return value
    }

    private fun parseTyped(
        t: Map<String, Any?>,
        where: String,
        file: File,
    ): ProfileTypedValue {
        unknownKeys(t, typedKeys, "$where typed_value", file)
        val typeName = text(t, "type", where, file)
        val type = ValueType.fromKeel(typeName) ?: error("gst-catalogue: $where typed_value type '$typeName' is unknown (INV-Q-2934).")
        val number =
            when (val raw = t["value"]) {
                is Int, is Long, is Double -> BigDecimal(raw.toString())
                else -> error("gst-catalogue: $where typed_value has no numeric 'value' in $file.")
            }
        return ProfileTypedValue(type, optText(t, "unit", where, file), number)
    }

    private fun parsePending(file: File): Map<Pair<String, String>, String> {
        val root = Yaml().load<Map<String, Any?>>(file.readText()) ?: emptyMap()
        val pending = map(root["pending"] ?: emptyMap<String, Any?>(), "pending", file)
        return pending.entries
            .flatMap { (prf, vps) ->
                map(vps, "pending $prf", file).entries.map { (vp, q) ->
                    val question = q as? String ?: error("gst-catalogue: $file marker $prf $vp is not a question key.")
                    if (!questionPattern.matches(question)) {
                        error("gst-catalogue: $file marker $prf $vp names '$question', not an INV-Q-nnnn question.")
                    }
                    (prf to vp) to question
                }
            }.toMap()
    }

    private fun map(
        v: Any?,
        where: String,
        file: File,
    ): Map<String, Any?> {
        val m = v as? Map<*, *> ?: error("gst-catalogue: $where in $file is not a mapping.")
        return m.entries.associate { (k, value) -> (k as? String ?: error("gst-catalogue: $where in $file has a non-text key '$k'.")) to value }
    }

    private fun unknownKeys(
        m: Map<String, Any?>,
        allowed: Set<String>,
        where: String,
        file: File,
    ) {
        val unknown = m.keys - allowed
        if (unknown.isNotEmpty()) {
            error("gst-catalogue: $where in $file has unknown field(s) ${unknown.sorted().joinToString()}: extend the generator, do not drop them.")
        }
    }

    private fun text(
        m: Map<String, Any?>,
        key: String,
        where: String,
        file: File,
    ): String = optText(m, key, where, file) ?: error("gst-catalogue: $where has no '$key' in $file.")

    private fun optText(
        m: Map<String, Any?>,
        key: String,
        where: String,
        file: File,
    ): String? {
        val v = m[key] ?: return null
        val s = v as? String ?: error("gst-catalogue: $where '$key' is not text in $file.")
        return s.trim().ifEmpty { error("gst-catalogue: $where '$key' is blank in $file.") }
    }

    private fun bool(
        m: Map<String, Any?>,
        key: String,
        where: String,
        file: File,
    ): Boolean = m[key]?.let { it as? Boolean ?: error("gst-catalogue: $where '$key' is not true/false in $file.") } ?: false

    private fun list(
        m: Map<String, Any?>,
        key: String,
        where: String,
        file: File,
    ): List<String> {
        val v = m[key] ?: return emptyList()
        val l = v as? List<*> ?: error("gst-catalogue: $where '$key' is not a list in $file.")
        return l.map { it as? String ?: error("gst-catalogue: $where '$key' holds a non-text item '$it' in $file.") }
    }
}
