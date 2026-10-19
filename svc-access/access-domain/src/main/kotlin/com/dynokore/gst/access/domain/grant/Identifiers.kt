package com.dynokore.gst.access.domain.grant

// CS-06: identifiers are value classes.

@JvmInline
value class TenantRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "tenant reference is required" }
    }
}

@JvmInline
value class EntityRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) {
            "entity reference is required, at most $MAX_LENGTH characters"
        }
    }

    private companion object {
        const val MAX_LENGTH = 64
    }
}

@JvmInline
value class GroupRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "group reference is required" }
    }
}

/** The identity (staff, external or cross-tenant) a grant is given to; the principal of the authority replica. */
@JvmInline
value class IdentityRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) {
            "identity reference is required, at most $MAX_LENGTH characters"
        }
    }

    private companion object {
        const val MAX_LENGTH = 64
    }
}

@JvmInline
value class GrantRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) {
            "grant reference is required, at most $MAX_LENGTH characters"
        }
    }

    private companion object {
        const val MAX_LENGTH = 64
    }
}
