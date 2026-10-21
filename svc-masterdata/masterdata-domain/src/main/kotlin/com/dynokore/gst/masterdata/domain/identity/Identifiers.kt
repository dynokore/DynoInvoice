package com.dynokore.gst.masterdata.domain.identity

/** A registered entity of the tenant, by the reference the structure uses (contract: `entityRef`, at most 40). */
@JvmInline
value class EntityRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX) { "an entity reference is 1 to $MAX characters" }
    }

    companion object {
        const val MAX = 40
    }
}

/**
 * The legal constitution an identity triple is configured for (INV-REQ-310). The published contract types it as a
 * string of at most 40 characters and publishes no value list, so it is carried as the code the caller gives; no value
 * is assumed here.
 */
@JvmInline
value class Constitution(
    val code: String,
) {
    init {
        require(code.isNotBlank() && code.length <= MAX) { "a constitution code is 1 to $MAX characters" }
    }

    companion object {
        const val MAX = 40
    }
}

/** The reference of a person's identity (the access-svc identity reference), compared as one person (INV-REQ-1007). */
@JvmInline
value class PersonRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX) { "a person reference is 1 to $MAX characters" }
    }

    /** The comparison form: two references that differ only in case or surrounding blanks name one person. */
    val normalised: String get() = value.trim().lowercase()

    fun isSamePersonAs(other: PersonRef): Boolean = normalised == other.normalised

    companion object {
        const val MAX = 64
    }
}
