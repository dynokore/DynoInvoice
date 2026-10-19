package com.dynokore.gst.controlPlane.domain.pack

/** The identity that performed an operation: the token's `sub`, never a request field (INV-REQ-2590 b2). */
@JvmInline
value class ActorId(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(Char::isISOControl)) {
            "an actor is 1..$MAX_LENGTH characters with no control character"
        }
    }

    override fun toString(): String = value

    companion object {
        /** `PackResponse.authors` and `signer` maxLength. */
        const val MAX_LENGTH = 128
    }
}

/** The pack type of the contract: a string of at most 32 characters (`PackResponse.packType`). */
@JvmInline
value class PackType(
    val value: String,
) {
    init {
        require(value.matches(PATTERN)) { "a pack type is 1..32 letters, digits, '-' or '_', starting with a letter" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
    }
}

/** The pack version of the contract: a string of at most 16 characters (`PackResponse.version`). */
@JvmInline
value class PackVersion(
    val value: String,
) {
    init {
        require(value.matches(PATTERN)) { "a pack version is 1..16 letters, digits, '.', '-' or '_'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,15}")
    }
}

/** The pack's opaque reference, pack type plus version (`PACK-SCOPE-2026.3`); at most 64 characters. */
@JvmInline
value class PackRef(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH) { "a pack reference is 1..$MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 64

        fun of(
            type: PackType,
            version: PackVersion,
        ): PackRef = PackRef("PACK-${type.value}-${version.value}")
    }
}

/** The minimum compatible engine version, a release-train version (INV-REQ-1094 b1); at most 32 characters. */
@JvmInline
value class MinEngineVersion(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(Char::isISOControl)) {
            "a minimum engine version is 1..$MAX_LENGTH characters with no control character"
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 32
    }
}

enum class PackStatus { DRAFT, SIGNED, PUBLISHED }
