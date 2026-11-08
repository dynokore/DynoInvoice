package com.dynokore.gst.tenancy.adapters.licence

import com.dynokore.gst.tenancy.application.installation.LicenceFileContent
import com.dynokore.gst.tenancy.application.installation.LicenceFileVerifier
import com.dynokore.gst.tenancy.application.installation.LicenceTrustAnchors
import com.dynokore.gst.tenancy.application.installation.LicenceVerification
import com.dynokore.platform.crypto.DetachedSignatureVerifier
import com.dynokore.platform.crypto.DetachedVerification
import com.dynokore.platform.crypto.SignatureAlgorithm
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.Locale

/**
 * The signed licence file of INV-Q-9821 A (format of INV-Q-8679 A): a UTF-8 JSON document with exactly the members
 * `licence` (an object of exactly licenceRef, installationRef, term and issuedAt, all text) and `signature` (the base64
 * of the detached CMS signature over the RFC 8785 canonical bytes of `licence`). Every check fails closed.
 */
class SignedLicenceFiles(
    private val verifier: DetachedSignatureVerifier,
    private val anchors: LicenceTrustAnchors,
    private val installationRef: () -> String?,
) : LicenceFileVerifier {
    private val json: JsonMapper =
        JsonMapper
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()

    override fun verify(
        licenceId: String,
        signedFile: ByteArray,
    ): LicenceVerification =
        try {
            val own = installationRef() ?: throw Refusal("the installation has no valid reference")
            val root = readFile(signedFile)
            val values = licenceValues(root)
            checkSignature(values, signature(root))
            checkBinding(values, licenceId, own)
            LicenceVerification.Valid(
                LicenceFileContent(
                    values.getValue("licenceRef"),
                    values.getValue("installationRef"),
                    values.getValue("term"),
                    values.getValue("issuedAt"),
                ),
            )
        } catch (refusal: Refusal) {
            LicenceVerification.Refused(refusal.message.orEmpty())
        }

    private fun readFile(signedFile: ByteArray): JsonNode {
        val root =
            try {
                json.readTree(signedFile)
            } catch (e: JacksonException) {
                throw Refusal("the file is not JSON", e)
            }
        if (!root.isObject || root.propertyNames().toSet() != FILE_MEMBERS) {
            throw Refusal("the file has not exactly the members licence and signature")
        }
        return root
    }

    private fun licenceValues(root: JsonNode): Map<String, String> {
        val licence = root.get("licence")
        if (!licence.isObject || licence.propertyNames().toSet() != LICENCE_MEMBERS) {
            throw Refusal("licence has not exactly the members licenceRef, installationRef, term and issuedAt")
        }
        return LICENCE_MEMBERS.associateWith { text(licence, it) ?: throw Refusal("$it is not text") }
    }

    private fun signature(root: JsonNode): ByteArray {
        val encoded = text(root, "signature")
        return try {
            Base64.getDecoder().decode(encoded ?: throw Refusal("signature is not base64"))
        } catch (e: IllegalArgumentException) {
            throw Refusal("signature is not base64", e)
        }
    }

    private fun checkSignature(
        values: Map<String, String>,
        signature: ByteArray,
    ) {
        val outcome =
            verifier.verify(
                canonical(values),
                signature,
                SignatureAlgorithm.ECDSA_P256_SHA256,
                anchors.certificatesDer(),
            )
        if (outcome !is DetachedVerification.Verified) {
            throw Refusal("the signature does not verify against a trusted signer")
        }
    }

    private fun checkBinding(
        values: Map<String, String>,
        licenceId: String,
        own: String,
    ) {
        if (values.getValue("licenceRef") != licenceId) throw Refusal("licenceId is not licence.licenceRef")
        if (values.getValue("installationRef") != own) throw Refusal("the licence is for another installation")
    }

    private class Refusal(
        reason: String,
        cause: Throwable? = null,
    ) : RuntimeException(reason, cause)

    private fun text(
        node: JsonNode,
        name: String,
    ): String? = node.get(name)?.takeIf { it.isString }?.asString()

    companion object {
        private val FILE_MEMBERS = setOf("licence", "signature")
        private val LICENCE_MEMBERS = setOf("licenceRef", "installationRef", "term", "issuedAt")

        /** RFC 8785 (JCS) bytes of an object whose values are all text: members sorted by UTF-16 code units. */
        fun canonical(members: Map<String, String>): ByteArray =
            members.entries
                .sortedWith { a, b -> a.key.compareTo(b.key) }
                .joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:${quote(value)}" }
                .toByteArray(Charsets.UTF_8)

        private fun quote(text: String): String {
            val out = StringBuilder("\"")
            for (c in text) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\b' -> out.append("\\b")
                    c == '\t' -> out.append("\\t")
                    c == '\n' -> out.append("\\n")
                    c == '\u000C' -> out.append("\\f")
                    c == '\r' -> out.append("\\r")
                    c < ' ' -> out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}
