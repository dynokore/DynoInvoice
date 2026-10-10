package com.dynokore.platform.outboxandqueue

import java.util.UUID

/** The Doc UUID of a document-scoped event (FR-K.3). */
@JvmInline
value class DocUuid(val value: UUID) {
    override fun toString(): String = value.toString()
}
