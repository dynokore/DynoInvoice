package com.dynokore.platform.adapterruntime

/**
 * The version tag of one interface contract implementation (INV-REQ-1354: "Every
 * interface contract is versioned"). Two distinct [AdapterVersion] values may be
 * routed to concurrently by a [CutoverRule] during a cutover.
 *
 * A raw `String` is never passed as a version in a port or router signature
 * (CS-06): this value class validates its format once, at construction.
 */
@JvmInline
value class AdapterVersion(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "AdapterVersion must not be blank" }
        require(value.none(Char::isWhitespace)) { "AdapterVersion must not contain whitespace: '$value'" }
    }

    override fun toString(): String = value
}
