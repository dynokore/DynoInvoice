package com.dynokore.gst.reconciliation.adapters.policy

import com.dynokore.gst.reconciliation.application.workpaper.SingleAttestationPolicy
import com.dynokore.gst.reconciliation.application.workpaper.SoleAttester
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.RelaxationReader
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate
import java.util.UUID

private const val SINGLE_ATTESTATION_VP = "VP-40"
private const val SOLE_OPERATOR_OPTION = "SINGLE_ATTESTATION_SOLE_OPERATOR"

/**
 * The sole-operator fact of an attestation (INV-REQ-994, INV-T-24437, INV-AC-24436). It is present only when the
 * single-attestation option of VP-40 is in force for the entity on [soleOperator]'s date and the relaxation behind it
 * holds an acknowledgement; the acknowledging user ID and date are copied from the earliest acknowledgement (an
 * identity and a date, never a role, INV-Q-10647). Fails closed (NIST SP 800-53 SI-10): an election without an
 * acknowledgement gives null, and an exception of either reader propagates.
 */
class PolicyReaderSingleAttestation(
    private val policy: PolicyReader,
    private val relaxations: RelaxationReader,
) : SingleAttestationPolicy {
    override fun soleOperator(
        tenant: String,
        entityRef: String,
        on: LocalDate,
    ): SoleAttester? {
        val scope = PolicyScope.entity(TenantId(UUID.fromString(tenant)), entityRef)
        val key = VariationPointKey(SINGLE_ATTESTATION_VP)
        val selected = policy.resolve(key, scope, on).selected.map { it.key }
        // minByOrNull keeps the first of equal instants: the recorded order breaks a tie
        val first =
            if (selected == listOf(SOLE_OPERATOR_OPTION)) {
                relaxations.acknowledgementsInForce(key, scope, on).minByOrNull { it.acknowledgedAt }
            } else {
                null
            }
        return first?.let {
            SoleAttester(
                acknowledgedBy = it.principal,
                acknowledgedOn = it.acknowledgedAt.atZone(BusinessDate.ZONE).toLocalDate(),
            )
        }
    }
}
