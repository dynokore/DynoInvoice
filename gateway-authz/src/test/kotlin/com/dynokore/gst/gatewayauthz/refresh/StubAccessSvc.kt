package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.keys.VerificationKey
import com.dynokore.gst.gatewayauthz.revocation.RevocationEntry
import com.dynokore.gst.gatewayauthz.revocation.RevocationPage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory access-svc that answers getVerificationKeys and getRevocationList as INV-API-4001 specifies: a full
 * snapshot without sinceVersion, the entries above sinceVersion otherwise, a snapshot when sinceVersion is above its
 * current version, pages of [pageSize]. [down] makes every call fail.
 */
class StubAccessSvc(
    private val pageSize: Int = 500,
) : KeyMaterialSource {
    @Volatile var down = false
    val entries = CopyOnWriteArrayList<RevocationEntry>()
    val calls = CopyOnWriteArrayList<Pair<Long?, String?>>()

    val version: Long get() = entries.maxOfOrNull { it.version } ?: 0

    fun revoke(entry: RevocationEntry) {
        entries += entry
    }

    @Volatile var keys: List<VerificationKey> = listOf(VerificationKey("2026-09", "RS256", byteArrayOf(1, 2, 3)))

    override fun verificationKeys(): List<VerificationKey> {
        check(!down) { "access-svc unreachable" }
        return keys
    }

    override fun revocations(
        sinceVersion: Long?,
        cursor: String?,
    ): RevocationPage {
        check(!down) { "access-svc unreachable" }
        calls += sinceVersion to cursor
        val pinned = cursor?.substringBefore(':')?.toLong() ?: version
        val offset = cursor?.substringAfter(':')?.toInt() ?: 0
        val snapshot = sinceVersion == null || sinceVersion > pinned
        val selected =
            entries
                .filter { it.version <= pinned && (snapshot || it.version > requireNotNull(sinceVersion)) }
                .sortedWith(compareBy({ it.version }, { it.entryId }))
        val page = selected.drop(offset).take(pageSize)
        val next = if (offset + pageSize < selected.size) "$pinned:${offset + pageSize}" else null
        return RevocationPage(pinned, snapshot, page, next)
    }
}
