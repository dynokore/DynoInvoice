package com.dynokore.gst.masterdata.application.registry

import com.dynokore.gst.masterdata.domain.registry.LookupEvidence
import com.dynokore.gst.masterdata.domain.registry.RegistryKind
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.ZoneId

/** The reachability fact of a Peppol ID; [degradationCode] is MDM-008 when the directory could not be reached. */
data class Reachability(
    val onNetwork: Boolean,
    val degradationCode: String?,
)

/** The catalogue code of a directory lookup that failed and was routed as off-network. */
const val MDM_008 = "MDM-008"

/** The business date of the policy reads: the Singapore date of the clock (INV-REQ-1351). */
internal fun Clock.businessDate() = instant().atZone(ZoneId.of("Asia/Singapore")).toLocalDate()

/**
 * INV-REQ-318, INV-REQ-319, INV-REQ-801, INV-REQ-802: resolves whether a Peppol ID is reachable on the network. An
 * answer inside its lifetime is served from the cache and makes no call. Otherwise the directory is asked, outside any
 * transaction and under the connection's deadline, the answer is cached with its lookup record and evidence, and an
 * outage routes the party off-network with MDM-008: an expired entry is never served stale, and nothing here stops
 * issuance. It returns the fact only; the Type 1A versus Type 2 decision is the routing domain's (INV-REQ-406).
 */
class ResolveReachability(
    private val tx: TransactionRunner,
    private val cache: DirectoryCache,
    private val client: DirectoryClient,
    private val policies: RegistryPolicies,
    private val evidence: LookupEvidenceLog,
    private val clock: Clock,
) {
    fun handle(
        entity: String,
        peppolId: String,
    ): Reachability {
        val served =
            tx.inTransaction {
                val lifetimes = policies.directoryLifetimes(entity, clock.businessDate())
                cache.find(peppolId)?.takeIf { lifetimes.serves(it.found, it.lookedUpAt, clock.instant()) }
            }
        if (served != null) return Reachability(served.found, null)
        val reply = client.lookup(peppolId)
        val at = clock.instant()
        return tx.inTransaction {
            when (reply) {
                is DirectoryReply.Found -> {
                    val key =
                        evidence.record(
                            entity,
                            LookupEvidence(RegistryKind.DIRECTORY, peppolId, reply.raw, at),
                            null,
                        )
                    cache.store(CachedDirectory(peppolId, true, reply.apReference, at, key))
                    Reachability(true, null)
                }

                is DirectoryReply.NotFound -> {
                    val key =
                        evidence.record(
                            entity,
                            LookupEvidence(RegistryKind.DIRECTORY, peppolId, reply.raw, at),
                            null,
                        )
                    cache.store(CachedDirectory(peppolId, false, null, at, key))
                    Reachability(false, null)
                }

                DirectoryReply.Unreachable -> {
                    evidence.record(entity, LookupEvidence(RegistryKind.DIRECTORY, peppolId, UNREACHABLE, at), MDM_008)
                    Reachability(false, MDM_008)
                }
            }
        }
    }

    private companion object {
        const val UNREACHABLE = "{\"outcome\":\"UNREACHABLE\"}"
    }
}
