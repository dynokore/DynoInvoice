package com.dynokore.gst.tenancy.application.installation

import java.time.Instant

/** One installation fact: release train and supported window, backup-target verification, platform VP-52 (INV-Q-4898). */
data class InstallationFact(
    val key: String,
    val value: String,
    val recordedAt: Instant,
) {
    init {
        require(key.length in KEY_LENGTH) { "a fact key has 1 to 100 characters" }
        require(value.length in VALUE_LENGTH) { "a fact value has 1 to 1000 characters" }
    }

    private companion object {
        val KEY_LENGTH = 1..100
        val VALUE_LENGTH = 1..1000
    }
}

/**
 * Persistence of installation facts. The table is installation-scoped, not tenant data (it carries no tenant column);
 * INV-T-3290 (installation-action operation) and the entitlement task write and read it through this port.
 */
interface InstallationFactRepository {
    /** Inserts the fact, or replaces the value of the fact with the same key. */
    fun put(fact: InstallationFact)

    fun find(key: String): InstallationFact?

    fun all(): List<InstallationFact>
}
