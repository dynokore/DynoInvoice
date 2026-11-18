package com.dynokore.gst.reconciliation.adapters.policy

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.reconciliation.application.workpaper.export.PolicyView
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxableControl
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * The [PolicyView] over the local policy replica (INV-REQ-982 item 3, event-carried state transfer). It is current only
 * when the replica measures its lag and the lag is within [maxLag]; an unknown lag is never current (fail closed).
 * The bound is configuration (12-factor III), never a constant here.
 */
class PolicyReplicaView(
    private val replica: PolicyReplica,
    private val catalogue: VariationPointCatalogue,
    private val maxLag: Duration,
) : PolicyView {
    override fun current(): Boolean =
        when (val lag = replica.lag()) {
            is Lag.Measured -> lag.duration <= maxLag
            Lag.Unknown -> false
        }

    override fun controls(): List<RelaxableControl> =
        catalogue
            .keys()
            .filter { key -> definitionOf(key).options.any { it.strictness == Strictness.LOOSER } }
            .map { key ->
                val variationPoint =
                    checkNotNull(VariationPoint.fromId(key.value)) {
                        "the variation point ${key.value} is not in the generated catalogue"
                    }
                RelaxableControl(code = key.value, description = variationPoint.title)
            }

    override fun relaxationAckRef(
        tenant: String,
        entityRef: String,
        code: String,
        on: LocalDate,
    ): String? =
        replica
            .valueAt(
                PolicyScope.entity(TenantId(UUID.fromString(tenant)), entityRef),
                VariationPointKey(code).value,
                on,
            )?.relaxationAckRef

    private fun definitionOf(key: VariationPointKey) = catalogue.require(key)
}
