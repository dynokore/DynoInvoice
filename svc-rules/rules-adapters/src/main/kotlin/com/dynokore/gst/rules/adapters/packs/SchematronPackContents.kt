package com.dynokore.gst.rules.adapters.packs

import java.security.MessageDigest
import java.util.Base64

/** One Schematron file of a pack, held byte for byte. */
class SchematronFile(
    val name: String,
    val content: ByteArray,
)

/** The decoded contents of a SCHEMATRON pack: the target version label and its files in pack order. */
class SchematronContents(
    val version: String,
    val files: List<SchematronFile>,
)

/**
 * Decodes the contents of a SCHEMATRON pack (`schematron-pack-contents.schema.json`, INV-REQ-471). Besides the schema
 * it checks what the schema cannot express: a `name` appears once and the `sha256` of each file equals the SHA-256 of
 * its decoded content. Every failure names the member. A member the schema does not name is ignored (API-41).
 */
object SchematronPackContents {
    private const val PACK_TYPE = "SCHEMATRON"
    private val NAME_PATTERN = Regex("^[A-Za-z0-9._-]+$")
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    fun decode(contents: ByteArray): SchematronContents {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val version = root.text("version", min = 1, max = 32)
        val nodes = root.array("files", min = 1, max = 200)
        val seen = mutableSetOf<String>()
        val files =
            nodes.mapIndexed { index, node ->
                val name = node.text("name", pattern = NAME_PATTERN, min = 1, max = 255)
                if (!seen.add(name)) node.fail("name", "is repeated: '$name'")
                val sha256 = node.text("sha256", pattern = SHA256_PATTERN)
                val encoded = node.text("content", min = 1)
                val bytes =
                    try {
                        Base64.getDecoder().decode(encoded)
                    } catch (e: IllegalArgumentException) {
                        throw PackContentsFailure(PACK_TYPE, "$.files[$index].content", "is not base64", e)
                    }
                val actual =
                    MessageDigest
                        .getInstance(
                            "SHA-256",
                        ).digest(bytes)
                        .let {
                            java.util.HexFormat
                                .of()
                                .formatHex(it)
                        }
                if (actual != sha256) node.fail("sha256", "differs from the SHA-256 of the content of '$name'")
                SchematronFile(name, bytes)
            }
        return SchematronContents(version, files)
    }
}
