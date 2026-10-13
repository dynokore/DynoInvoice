package com.dynokore.platform.serviceruntime.events

import com.dynokore.platform.outboxandqueue.EventContext
import java.security.MessageDigest
import java.util.Locale

/**
 * `artefactType` of `EvidenceArtefactRecorded` (INV-API-3310, INV-Q-3341): an extensible enumeration (API-09
 * UPPER_SNAKE_CASE). A producer names one of the [known] values; a reader keeps a value it does not know as
 * received, never drops or rewrites it, so evidence-svc's uniqueness on (artefactType, reference, hash) holds.
 */
@JvmInline
value class EvidenceArtefactType(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "artefact type is UPPER_SNAKE_CASE (API-09)" }
    }

    override fun toString(): String = value

    /** True when this value is one of the initial values of INV-API-3310. */
    val isKnown: Boolean get() = this in known

    companion object {
        private val PATTERN = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")

        /** A payload-svc rendition (INV-REQ-1036). */
        val RENDITION = EvidenceArtefactType("RENDITION")

        /** A rendition sample check with its samples and result, payload-svc (INV-REQ-1036 b2, INV-AC-2010). */
        val SAMPLE_CHECK = EvidenceArtefactType("SAMPLE_CHECK")

        /** A Peppol directory or Check GST Register lookup, masterdata-svc (INV-REQ-802 b2, INV-REQ-478). */
        val REGISTER_LOOKUP = EvidenceArtefactType("REGISTER_LOOKUP")

        /** An effective-dated exchange-rate record with its load provenance, masterdata-svc (INV-REQ-506 b3). */
        val RATE_RECORD = EvidenceArtefactType("RATE_RECORD")

        /** An import record: masterdata-svc counterparty imports, rules-svc mapping-pack re-imports (INV-REQ-833). */
        val IMPORT = EvidenceArtefactType("IMPORT")

        /** A client signatory's acknowledge, query or withdraw-consent, access-svc (INV-REQ-900 b3, INV-REQ-997 b2). */
        val SIGNATORY_ACTION = EvidenceArtefactType("SIGNATORY_ACTION")

        val known: Set<EvidenceArtefactType> =
            setOf(RENDITION, SAMPLE_CHECK, REGISTER_LOOKUP, RATE_RECORD, IMPORT, SIGNATORY_ACTION)
    }
}

/**
 * The payload of `EvidenceArtefactRecorded` (platform-contracts `evidence-artefact-recorded-payload.schema.json`,
 * INV-API-3310): all three fields required. [hash] is the SHA-256 of the artefact bytes as 64 lowercase hex
 * characters, the platform's one algorithm (INV-REQ-650 b2, INV-AC-2206), so every producer hashes the same
 * bytes to the same value.
 */
data class EvidenceArtefact(
    val type: EvidenceArtefactType,
    val reference: String,
    val hash: String,
) {
    init {
        require(reference.isNotBlank()) { "artefact reference must not be blank" }
        require(HASH.matches(hash)) { "hash is a SHA-256 as 64 lowercase hex characters" }
    }

    companion object {
        private val HASH = Regex("^[0-9a-f]{64}$")

        /** The artefact recorded from its [bytes], hashed with SHA-256 (INV-REQ-650 b2). */
        fun of(
            type: EvidenceArtefactType,
            reference: String,
            bytes: ByteArray,
        ): EvidenceArtefact = EvidenceArtefact(type, reference, sha256Hex(bytes))

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
        }
    }
}

/**
 * Publishes `EvidenceArtefactRecorded` (INV-REQ-1226 b1-b2, INV-API-3310) for evidence-svc: at-least-once through
 * the producing service's transactional outbox, in the transaction that records the artefact (INV-ADR-283). The
 * caller has bound the tenant and opened that transaction (ARC-21, ARC-05); [context] is the artefact's.
 */
interface EvidenceArtefactRecorder {
    fun record(
        artefact: EvidenceArtefact,
        context: EventContext,
    )
}
