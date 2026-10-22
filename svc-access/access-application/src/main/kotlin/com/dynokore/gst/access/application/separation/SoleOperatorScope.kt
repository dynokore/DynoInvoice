package com.dynokore.gst.access.application.separation

/**
 * The values of VP-81 (INV-REQ-8287): who may hold `security.officer` and `tenant.admin` together under the sole-operator
 * acknowledgement and what withdrawing the acknowledgement does. The constants are the option keys of the catalogue row.
 */
enum class SoleOperatorScope {
    /** At most one person holds both roles; withdrawal is refused while anyone holds both (the default). */
    ONE_HOLDER_WITHDRAW_BLOCKED,

    /** At most one person holds both roles; withdrawal needs a second holder and then changes no grant. */
    ONE_HOLDER,

    /** Any number of people hold both roles; withdrawal is allowed at any time and changes no grant. */
    ANY_NUMBER,
}

/** The tenant's VP-81 value in force today. */
fun interface SoleOperatorScopePolicy {
    /** @throws RuntimeException when the value cannot be read or is not one of [SoleOperatorScope]: never a default. */
    fun current(): SoleOperatorScope
}
