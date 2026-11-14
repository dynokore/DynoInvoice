package com.dynokore.gst.submission.adapters.policy

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.application.filing.GateModesAt
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/**
 * [GateModesAt] over the configuration resolver (INV-AC-22488): VP-14 is resolved at entity scope on the transaction
 * date passed in (INV-REQ-1351; the adapter reads no clock). Fail closed: a resolver exception, no selected option,
 * more than one, or a key [GateMode.fromKey] refuses throws; there is no default mode.
 */
class PolicyGateModes(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
) : GateModesAt {
    override fun modeAt(
        entityRef: String,
        on: LocalDate,
    ): GateMode {
        val scope = PolicyScope.entity(tenants.current(), entity = entityRef)
        val selected = reader.resolve(VariationPointKey(KEY), scope, on).selected
        check(selected.size == 1) { "VP-14 must select exactly one option, got ${selected.size}" }
        return GateMode.fromKey(selected.single().key)
    }

    companion object {
        /** VP-14, read from the generated catalogue. */
        val KEY: String = VariationPoint.VP_14.id
    }
}
