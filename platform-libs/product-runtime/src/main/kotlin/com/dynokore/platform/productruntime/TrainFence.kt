package com.dynokore.platform.productruntime

/**
 * The fence participants of a release train's pack activation (INV-REQ-1166, §5.5; INV-MOD-2745): `rules-svc`
 * stages and arms, and every pack-holding instance verifies, loads, compiles and acknowledges with
 * `PackReadinessAcknowledged` before the effective date is armed (INV-REQ-1166 b1, b2; INV-MOD-2373).
 */
object TrainFence {
    /** The sole PackSource client, which stages the pack and arms `effective_from` (INV-REQ-1166 b1, b2). */
    const val ARMER = "rules-svc"

    /** The pack holders (INV-REQ-1166 b1, INV-MOD-2373). */
    val PACK_HOLDERS: Set<String> =
        linkedSetOf("payload-svc", "tenancy-svc", "reconciliation-svc", "notification-svc")

    /**
     * Every service under the fence for a train holding [servicesInTrain]: `rules-svc`, which arms, and the pack
     * holders present in the train (INV-REQ-1166 b2).
     */
    fun participants(servicesInTrain: Set<String>): Set<String> {
        val holders = PACK_HOLDERS.filter { it in servicesInTrain }
        return linkedSetOf(ARMER) + holders
    }

    /** True when [service] holds packs and must acknowledge readiness before the arm (INV-REQ-1166 b1). */
    fun holdsPacks(service: String): Boolean = service in PACK_HOLDERS
}
