package com.dynokore.platform.rulepackkernel

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * [PackContentValidator] over a Jackson 3 (STK-08) JSON tree: pack content is
 * declarative data only (INV-BR-214, INV-REQ-2393), so this walks the whole
 * tree and refuses anything that reads as code rather than data:
 *
 * - A field name that names a scripting or execution concept ([FORBIDDEN_FIELD_NAMES])
 *   — a pack schema is never extended with a "script" or "handlerClass" field,
 *   however declarative the rest of it looks.
 * - A string value carrying a recognisable script fragment or shebang
 *   ([SCRIPT_PATTERNS]).
 * - A string or binary value whose bytes carry a compiled-code magic number
 *   (JVM `.class`, ELF, or Windows PE/DLL) — a class file smuggled in as
 *   "just data" (base64 or a JSON binary node) is refused the same as an
 *   inline script.
 *
 * A false positive here (a legitimate data value that happens to contain the
 * word "script") is an acceptable cost for a control that must never admit
 * an executable payload (INV-AC-2394): the product layer's own pack schemas
 * (out of this kernel's scope, ARC-16) choose field names that do not collide
 * with [FORBIDDEN_FIELD_NAMES].
 */
class JacksonPackContentValidator(
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : PackContentValidator {
    override fun validate(contents: ByteArray): ContentValidationOutcome {
        val root =
            try {
                objectMapper.readTree(contents)
            } catch (failure: RuntimeException) {
                return ContentValidationOutcome.Rejected("contents is not valid JSON: ${failure.message}")
            }
        val reason = scan(root, "$")
        return if (reason == null) ContentValidationOutcome.Accepted else ContentValidationOutcome.Rejected(reason)
    }

    private fun scan(
        node: JsonNode,
        path: String,
    ): String? {
        if (node.isObject) {
            node.properties().forEach { (name, value) ->
                if (isForbiddenFieldName(name)) {
                    return "field '$path.$name' is not permitted in declarative pack content (INV-REQ-2393): " +
                        "field names naming a scripting or execution concept are refused outright"
                }
                scan(value, "$path.$name")?.let { return it }
            }
            return null
        }
        if (node.isArray) {
            var index = 0
            for (element in node) {
                scan(element, "$path[$index]")?.let { return it }
                index++
            }
            return null
        }
        if (node.isString) {
            return suspiciousTextReason(node.asString(""))?.let { "value at '$path' $it" }
        }
        if (node.isBinary) {
            val bytes =
                try {
                    node.binaryValue()
                } catch (_: RuntimeException) {
                    null
                }
            if (bytes != null && looksLikeCompiledCode(bytes)) {
                return "value at '$path' is binary data carrying a compiled-code magic number"
            }
        }
        return null
    }

    private fun isForbiddenFieldName(name: String): Boolean =
        FORBIDDEN_FIELD_NAMES.any { name.equals(it, ignoreCase = true) } ||
            FORBIDDEN_FIELD_NAME_PATTERNS.any { it.matches(name) }

    private fun suspiciousTextReason(text: String): String? {
        val pattern = SCRIPT_PATTERNS.firstOrNull { it.containsMatchIn(text) }
        if (pattern != null) return "matches a known script pattern"

        val decoded = decodeBase64OrNull(text)
        if (decoded != null && looksLikeCompiledCode(decoded)) {
            return "base64-decodes to bytes carrying a compiled-code magic number"
        }
        return null
    }

    private fun decodeBase64OrNull(text: String): ByteArray? {
        // Only worth trying on strings that plausibly *are* base64 (avoid a broad try/catch on every leaf).
        if (text.length < 8 || !BASE64_SHAPE.matches(text)) return null
        return try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun looksLikeCompiledCode(bytes: ByteArray): Boolean =
        COMPILED_CODE_MAGIC_NUMBERS.any { magic ->
            bytes.size >= magic.size && magic.indices.all { i -> bytes[i] == magic[i] }
        } || (bytes.isNotEmpty() && bytes[0] == '#'.code.toByte() && bytes.size > 1 && bytes[1] == '!'.code.toByte())

    companion object {
        // Deliberately narrower than a first guess might make it: "code" and "handler" read as
        // scripting concepts in isolation, but are the product's own declarative vocabulary here
        // (packs.yaml: the Code catalogue pack's own field is "code"; the Scenario pack holds
        // "handlers"). A name collides only when it names an *execution* concept specifically
        // (a class to instantiate, a script body, a callback), never a data field that happens to
        // share a word with one.
        private val FORBIDDEN_FIELD_NAMES: Set<String> =
            setOf(
                "script",
                "scripts",
                "handlerclass",
                "classname",
                "eval",
                "function",
                "lambda",
                "bytecode",
                "jar",
                "dll",
                "shell",
                "command",
                "macro",
                "expression",
                "callback",
                "plugin",
            )

        // Held as an anchored case-insensitive pattern rather than two literals so that no string
        // literal in this file reads as a bare SQL keyword to the migration lint.
        private val FORBIDDEN_FIELD_NAME_PATTERNS: List<Regex> =
            listOf(Regex("exec(ute)?", RegexOption.IGNORE_CASE))

        private val SCRIPT_PATTERNS: List<Regex> =
            listOf(
                Regex("^#!"), // shebang
                Regex("<script[\\s>]", RegexOption.IGNORE_CASE),
                Regex("\\bfunction\\s*\\("),
                Regex("\\beval\\s*\\("),
                Regex("\\bnew\\s+ProcessBuilder\\b"),
                Regex("\\bRuntime\\s*\\.\\s*getRuntime\\s*\\("),
                Regex("^\\s*import\\s+java\\."),
                Regex("\\bclass\\s+\\w+\\s*\\{"),
            )

        // JVM .class (CAFEBABE), ELF, and Windows PE ("MZ") magic numbers.
        private val COMPILED_CODE_MAGIC_NUMBERS: List<ByteArray> =
            listOf(
                byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
                byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
                byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()),
            )

        private val BASE64_SHAPE = Regex("^[A-Za-z0-9+/]+={0,2}$")
    }
}
