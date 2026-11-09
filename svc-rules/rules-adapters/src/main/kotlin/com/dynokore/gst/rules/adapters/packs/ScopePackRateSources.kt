package com.dynokore.gst.rules.adapters.packs

/**
 * Decodes the `exchangeRateSources` of a SCOPE pack (`scope-pack-contents.schema.json`, INV-REQ-504): the
 * (sourceId, sourceClass) pairs, in the order of the pack. Every other member of an item is read by others and ignored
 * here (API-41). A contents that is not JSON, a missing or empty list, an item without a `sourceId` of the schema
 * pattern, or a `sourceClass` outside PRIMARY, SECONDARY and INFERRED is a decode failure: the answer is null and
 * never a partial list, so the caller cannot answer (fail closed). The member `exchangeRateSource` is not read.
 */
object ScopePackRateSources {
    private const val PACK_TYPE = "SCOPE"
    private const val MAX_ITEMS = 50
    private val SOURCE_ID = Regex("^[A-Za-z][A-Za-z0-9._-]{0,39}$")

    fun decode(contents: ByteArray): List<Pair<String, String>>? =
        try {
            ContentsNode
                .root(PACK_TYPE, contents)
                .array("exchangeRateSources", min = 1, max = MAX_ITEMS)
                .map { it.text("sourceId", pattern = SOURCE_ID) to it.text("sourceClass", oneOf = SOURCE_CLASSES) }
        } catch (_: PackContentsFailure) {
            null
        }
}
