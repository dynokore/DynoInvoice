package com.dynokore.gst.controlPlane.domain.release

/** The id of a historical document of the regeneration corpus. */
@JvmInline
value class DocumentId(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX) { "a document id is 1 to $MAX characters" }
    }

    override fun toString() = value

    private companion object {
        const val MAX = 128
    }
}

/**
 * The regeneration check (INV-REQ-1096, INV-NOTE-3 §13.4): the classification of every document of the historical
 * corpus recorded before an engine change is compared, document by document, with the one derived after it. Any
 * difference fails the release. A document present on one side only differs as well (it was lost or invented).
 */
object RegenerationCheck {
    fun differences(
        before: Map<DocumentId, String>,
        after: Map<DocumentId, String>,
    ): Set<DocumentId> = (before.keys + after.keys).filterTo(LinkedHashSet()) { before[it] != after[it] }

    /** An empty corpus proves nothing, so it is a failure naming no document (fail closed). */
    fun run(
        before: Map<DocumentId, String>,
        after: Map<DocumentId, String>,
    ): Set<String> =
        if (before.isEmpty()) {
            setOf(EMPTY_CORPUS)
        } else {
            differences(before, after).mapTo(LinkedHashSet()) { it.value }
        }

    const val EMPTY_CORPUS = "<empty regeneration corpus>"
}
