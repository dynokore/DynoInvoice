package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.keys.VerificationKey
import com.dynokore.gst.gatewayauthz.revocation.RevocationPage

/**
 * access-svc's key-material queries on the api-gateway -> access-svc edge (x-principal workload, owner ruling
 * INV-Q-4006 A): getVerificationKeys and getRevocationList (INV-API-4001). Any failure, a non-200 or a timeout, is
 * thrown; the caller keeps its cache (INV-Q-3973 A).
 */
interface KeyMaterialSource {
    fun verificationKeys(): List<VerificationKey>

    /** One page: [sinceVersion] null asks for a full snapshot; [cursor] continues a multi-page response. */
    fun revocations(
        sinceVersion: Long?,
        cursor: String?,
    ): RevocationPage
}
