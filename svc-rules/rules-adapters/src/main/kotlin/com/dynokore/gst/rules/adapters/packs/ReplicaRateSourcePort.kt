package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The contents of the SCOPE pack in force for [tenant] on [asOf], or null when none is in force or it is not held. */
fun interface ScopePackInForce {
    fun contents(
        tenant: TenantId,
        asOf: LocalDate,
    ): ByteArray?
}

/**
 * The rate-source block of INV-REQ-504 for one tenant (INV-Q-14651 A, INV-Q-16533 A): the ids of the sources that an
 * entity of the tenant has elected (attribute `exchangeRateSource` in force on `asOf`, INV-REQ-1285) and that the scope
 * pack in force lists as INFERRED. A tenant with no election gets the empty set. No acceptance is read, because none
 * can be recorded (owner question INV-CS-16528): every elected INFERRED source counts as unaccepted. Null when the
 * answer cannot be given: the replica is not synced, no scope pack is in force, or its contents do not decode.
 * A retired node and a node of another kind are ignored, as svc-aggregation's StructureCodeLookup ignores a
 * retired one.
 */
class ReplicaRateSourcePort(
    private val structure: StructureReplica,
    private val scopePack: ScopePackInForce,
) : RateSourcePort {
    override fun blocking(
        tenant: TenantId,
        asOf: LocalDate,
    ): List<String>? {
        val listed =
            (if (structure.isSynced()) scopePack.contents(tenant, asOf) else null)?.let(ScopePackRateSources::decode)
                ?: return null
        val elected = electedBy(tenant, asOf)
        return listed
            .filter { (id, sourceClass) ->
                sourceClass == INFERRED && id in elected
            }.map { it.first }
            .distinct()
    }

    private fun electedBy(
        tenant: TenantId,
        asOf: LocalDate,
    ): Set<String> =
        structure.replica
            .heldWhere { it.tenant == tenant }
            .values
            .filter { it.kind == StructureNodeKind.ENTITY && !it.retired }
            .mapNotNull { it.attribute(ATTRIBUTE, asOf) }
            .toSet()

    companion object {
        const val ATTRIBUTE = "exchangeRateSource"
        private const val INFERRED = "INFERRED"
    }
}
