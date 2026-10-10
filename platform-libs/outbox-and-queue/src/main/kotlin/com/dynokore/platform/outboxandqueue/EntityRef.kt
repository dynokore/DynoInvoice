package com.dynokore.platform.outboxandqueue

/** The registered entity an event concerns (FR-K.3, CloudEvents attribute `entityref`). */
@JvmInline
value class EntityRef(val value: String) {
    init {
        require(value.isNotBlank()) { "entity reference must not be blank" }
    }

    override fun toString(): String = value
}
