package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId

/**
 * For tests only (INV-T-19621, INV-AC-19620): builds a MACHINE [Principal] for the pin tests of the services that serve
 * a machine route. In production the only path to a machine principal is the verified machine access token; this
 * factory is published as a test-fixtures artifact, so no production API of service-runtime exposes it.
 */
object MachinePrincipalFixtures {
    fun machine(
        id: String,
        tenant: TenantId?,
        coverage: MachineEntityCoverage,
        machineType: String,
        scope: String? = null,
        tokenId: String? = null,
    ): Principal = Principal.machine(id, tenant, coverage, machineType, scope, tokenId)
}
