package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.PURCHASE_DATE_BASIS_ATTRIBUTE
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.domain.ElectionKind

/**
 * The purchaseDateBasis entries of an entity (VP-22), one per effective-dated election, read from the one election
 * store: the live profile publishers and the replay of EntityProfileChanged carry the same entries (INV-AC-21460).
 */
class PurchaseDateBasisProfileExtras(
    private val elections: ElectionPolicyStore,
) : ProfileExtras {
    override fun entries(entityRef: String): List<ProfileEntry> =
        elections
            .history(entityRef, ElectionKind.PURCHASE_DATE_BASIS)
            .map { ProfileEntry(PURCHASE_DATE_BASIS_ATTRIBUTE, it.value, it.effectiveFrom) }
}
