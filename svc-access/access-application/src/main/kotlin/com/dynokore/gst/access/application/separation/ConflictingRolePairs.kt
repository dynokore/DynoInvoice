package com.dynokore.gst.access.application.separation

/** The tenant's VP-82 value in force today: the further pairs of roles one person may not hold together. */
fun interface ConflictingRolePairs {
    /** @throws RuntimeException when the value cannot be read or is not a known one: never a default. */
    fun current(): Set<RolePair>

    companion object {
        /** No further pair: the value of VP-82 by default. */
        val NONE = ConflictingRolePairs { emptySet() }
    }
}
