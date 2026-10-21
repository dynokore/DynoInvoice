package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.keys.VerificationKey
import com.dynokore.gst.gatewayauthz.revocation.RevocationEntry
import com.dynokore.gst.gatewayauthz.revocation.RevocationKind
import com.dynokore.gst.gatewayauthz.revocation.RevocationPage
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

/**
 * The api-gateway -> access-svc edge (INV-API-4001, x-principal workload, x-timeout 2000): getVerificationKeys and
 * getRevocationList over [client]. The client is built from service-runtime's internal builder, so every call carries
 * the pod's projected service-account token in `X-Workload-Token` and nothing else (owner ruling INV-Q-4006 A). Every
 * non-200 answer and every timeout is thrown, and the caller keeps its cache (INV-Q-3973 A, INV-Q-4021 A).
 */
class AccessSvcClient(
    private val client: RestClient,
) : KeyMaterialSource {
    override fun verificationKeys(): List<VerificationKey> {
        val response =
            client
                .get()
                .uri("/internal/v1/access/verification-keys")
                .retrieve()
                .body(VerificationKeysWire::class.java)
        return requireNotNull(response) { "getVerificationKeys answered without a body" }.keys.map {
            VerificationKey(it.kid, it.alg, it.publicKey)
        }
    }

    override fun revocations(
        sinceVersion: Long?,
        cursor: String?,
    ): RevocationPage {
        val response =
            client
                .get()
                .uri { uri ->
                    uri.path("/internal/v1/access/revocations").queryParam("limit", PAGE_LIMIT)
                    sinceVersion?.let { uri.queryParam("sinceVersion", it) }
                    cursor?.let { uri.queryParam("cursor", it) }
                    uri.build()
                }.retrieve()
                .body(object : ParameterizedTypeReference<RevocationListWire>() {})
        val page = requireNotNull(response) { "getRevocationList answered without a body" }
        return RevocationPage(page.listVersion, page.snapshot, page.items.map { it.toEntry() }, page.nextCursor)
    }

    private fun RevocationEntryWire.toEntry() =
        RevocationEntry(
            entryId,
            version,
            RevocationKind.fromWire(kind),
            principalRef,
            subject,
            tokenId,
            revokedAt,
            expiresAt,
        )

    private companion object {
        /** The operation's maximum page size (INV-API-4001). */
        const val PAGE_LIMIT = 500
    }
}

internal data class VerificationKeysWire(
    val keys: List<VerificationKeyWire>,
)

/** [publicKey] is the DER X.509 SubjectPublicKeyInfo in standard base64 (INV-Q-4079); Jackson decodes it to bytes. */
internal class VerificationKeyWire(
    val kid: String,
    val alg: String,
    val publicKey: ByteArray,
)

internal data class RevocationListWire(
    val listVersion: Long,
    val snapshot: Boolean,
    val items: List<RevocationEntryWire>,
    val nextCursor: String? = null,
)

internal data class RevocationEntryWire(
    val entryId: UUID,
    val version: Long,
    val kind: String,
    val principalRef: String? = null,
    val subject: String? = null,
    val tokenId: String? = null,
    val revokedAt: Instant,
    val expiresAt: Instant,
)
