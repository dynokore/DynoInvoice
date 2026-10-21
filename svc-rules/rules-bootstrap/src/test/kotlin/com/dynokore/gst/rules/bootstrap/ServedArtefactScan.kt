package com.dynokore.gst.rules.bootstrap

import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Never served" (INV-AC-2182, INV-T-7525): no HTTP operation of rules-svc returns artefact content. The contract files
 * of `platform-contracts/rules/` at the pinned commit are read as the rules-contract module ships them, and no schema
 * of `openapi.yaml` may have a property that carries artefact content. The one file of the directory that has such
 * properties is the payload of `RulePackStaged`, named here by its path and by nothing else.
 */
internal object ServedArtefactScan {
    /** The property names of the four signed members of an artefact (STK-13). */
    val ARTEFACT_PROPERTIES = setOf("manifest", "checksums", "signature", "contents")

    /** The staging event's payload (INV-REQ-1195, INV-REQ-1196): the only file that may name them. */
    const val ALLOWED = "rules/schemas/rule-pack-staged-payload.schema.json"

    const val OPENAPI = "rules/openapi.yaml"

    /** The contract files of the pinned bundle, by their path under `platform-contracts/`, with their text. */
    fun pinnedFiles(): Map<String, String> {
        val resource = checkNotNull(ServedArtefactScan::class.java.getResource("/contract")) { "no /contract resource" }
        val root = resource.toURI()
        return withRoot(root) { dir ->
            Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .toList()
                    .associate { "rules/" + dir.relativize(it).joinToString("/") to Files.readString(it) }
            }
        }
    }

    private fun <T> withRoot(
        uri: URI,
        body: (Path) -> T,
    ): T =
        if (uri.scheme == "jar") {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { body(it.getPath("/contract")) }
        } else {
            body(Path.of(uri))
        }

    /** Findings for [files] (path to text): every property named like an artefact member outside [ALLOWED]. */
    fun findings(files: Map<String, String>): List<String> {
        val findings = mutableListOf<String>()
        val holders = mutableSetOf<String>()
        files.forEach { (path, text) ->
            val names = propertyNames(Yaml().load<Any?>(text))
            val hit = names.filter { it in ARTEFACT_PROPERTIES }.toSortedSet()
            if (hit.isNotEmpty()) holders += path
            if (hit.isNotEmpty() && path != ALLOWED) findings += "$path has the properties $hit"
        }
        if (ALLOWED !in files) findings += "$ALLOWED is not in the pinned contract"
        if (ALLOWED in files && ALLOWED !in holders) findings += "$ALLOWED no longer has artefact properties"
        return findings
    }

    /** Every key of every `properties` mapping anywhere in the document. */
    private fun propertyNames(node: Any?): List<String> =
        when (node) {
            is Map<*, *> -> {
                node.entries.flatMap { (key, value) ->
                    val own =
                        if (key == "properties" && value is Map<*, *>) value.keys.map { it.toString() } else emptyList()
                    own + propertyNames(value)
                }
            }

            is List<*> -> {
                node.flatMap { propertyNames(it) }
            }

            else -> {
                emptyList()
            }
        }
}
