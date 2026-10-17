package com.dynokore.platform.adapterruntime

/** A transaction as the source system holds it: its key and the tax code of each line, in order. */
data class SourceTransaction(
    val sourceKey: String,
    val lineTaxCodes: List<String>,
)

enum class PassThroughViolationKind { DROPPED, RECODED, LINE_COUNT_CHANGED, UNKNOWN_EVENT }

data class PassThroughViolation(
    val kind: PassThroughViolationKind,
    val sourceKey: String?,
    val detail: String,
)

/**
 * Checks that an adapter dropped, filtered or recoded no transaction by tax treatment
 * (INV-REQ-800, INV-AC-1854, INV-AC-2032). Tax codes are compared by exact string
 * equality; this verifier holds no list of codes, scope rule or mapping: the rules
 * plane decides.
 */
object TaxPassThroughVerifier {
    fun verify(
        source: List<SourceTransaction>,
        emitted: List<UpstreamEvent>,
    ): List<PassThroughViolation> {
        val keys = source.map { it.sourceKey }
        require(keys.size == keys.toSet().size) { "source transactions must have distinct source keys" }
        val byKey = source.associateBy { it.sourceKey }
        val violations = mutableListOf<PassThroughViolation>()
        val emittedKeys = emitted.mapNotNull { it.sourceKey }.toSet()

        source.filter { it.sourceKey !in emittedKeys }.forEach {
            violations +=
                PassThroughViolation(
                    PassThroughViolationKind.DROPPED,
                    it.sourceKey,
                    "no event emitted for the source key",
                )
        }
        emitted.forEach { event -> checkEvent(event, byKey)?.let { violations += it } }
        return violations
    }

    private fun checkEvent(
        event: UpstreamEvent,
        byKey: Map<String, SourceTransaction>,
    ): PassThroughViolation? {
        val key = event.sourceKey
        val src = key?.let { byKey[it] }
        val codes = event.lines.map { it.taxCode }
        return when {
            src == null -> {
                PassThroughViolation(
                    PassThroughViolationKind.UNKNOWN_EVENT,
                    key,
                    "emitted event key is in no source transaction",
                )
            }

            codes.size != src.lineTaxCodes.size -> {
                PassThroughViolation(
                    PassThroughViolationKind.LINE_COUNT_CHANGED,
                    key,
                    "source has ${src.lineTaxCodes.size} lines, event has ${codes.size}",
                )
            }

            codes != src.lineTaxCodes -> {
                PassThroughViolation(PassThroughViolationKind.RECODED, key, "line tax codes differ from the source")
            }

            else -> {
                null
            }
        }
    }
}
