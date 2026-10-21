package com.dynokore.gst.transport.domain

import java.util.UUID

/** The Doc UUID and version a payload is submitted for: transport never decides either (transport-svc `must_not`). */
data class DocRef(
    val docUuid: UUID,
    val version: Int,
) {
    init {
        require(version >= 1) { "a version starts at 1, got $version" }
    }
}

/** The id of an Access Point adapter, as the registry and the attempt log name it. */
@JvmInline
value class AdapterId(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "an adapter id is 1 to $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 40
    }
}

/** The entity a submission is made for; it selects the adapter (INV-REQ-796) and is never sent to the AP. */
@JvmInline
value class EntityRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "an entity ref is 1 to $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 40
    }
}
