package com.dynokore.platform.productruntime.fallback

import com.dynokore.gst.productRuntimeTenancyClient.adapters.clients.api.EntitiesApi
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureFallback
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClientResponseException
import com.dynokore.gst.productRuntimeTenancyClient.adapters.clients.model.StructureNode as TenancyNode

/**
 * The structure replica's fallback (INV-REQ-1200 b2, AD-26): `getEntityStructure` on tenancy-svc over the generated
 * client (INV-Q-3852), for a node the replica does not hold. service-runtime runs it through the tenancy-svc edge's
 * policy (500 ms, one retry; INV-NOTE-3 §36.6), so a timeout, an open breaker or any failure is Unavailable and the
 * replica keeps serving what it holds. A 404 is NotFound; a 200 without the node does not meet the contract and is
 * Unavailable, never a guessed node.
 */
class TenancyStructureFallback(
    private val api: EntitiesApi,
) : StructureFallback {
    override fun fetch(
        key: StructureKey,
        deadline: Deadline,
    ): FallbackResult<StructureNode> {
        val response =
            try {
                api.getEntityStructure(key.nodeRef)
            } catch (e: RestClientResponseException) {
                if (e.statusCode.value() != HttpStatus.NOT_FOUND.value()) throw e
                null
            }
        return when {
            response == null -> FallbackResult.NotFound
            else -> response.node?.let { FallbackResult.Found(it.toReplicaNode()) } ?: FallbackResult.Unavailable
        }
    }

    private fun TenancyNode.toReplicaNode() =
        StructureNode(
            kind = StructureNodeKind.of(kind.value),
            parentRef = parentRef,
            retired = retired,
            name = name,
            code = code,
            profile = profile.map { ProfileEntry(it.attribute, it.`value`, it.effectiveDate) },
            structureVersion = structureVersion,
        )
}
