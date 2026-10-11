package com.dynokore.platform.configresolver

/** Guards the one place SQL text is assembled from a caller-supplied name (a schema or a role). */
internal object SqlIdentifier {
    private val PLAIN = Regex("^[a-z][a-z0-9_]{0,62}$")

    fun require(
        name: String,
        what: String,
    ): String {
        require(PLAIN.matches(name)) { "$what must be a plain lower-case identifier of at most 63 chars, was '$name'" }
        return name
    }
}
