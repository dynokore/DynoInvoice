package com.dynokore.gst.canonical.application.source

import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** Port of the `source_event` table (canonical schema). */
interface SourceEventStore {
    /**
     * Takes a transaction-scoped lock on (tenant, source_system, source_key), not on the revision; the commit or the
     * rollback releases it (INV-Q-8114). Concurrent first records of one source key are serialised by it.
     */
    fun lockSourceKey(key: SourceKey)

    /** The Doc UUID of the row with exactly this four-part key, if any. */
    fun docUuidOf(key: SourceKey): UUID?

    /** The Doc UUID bound to any revision of (tenant, source_system, source_key), if any. */
    fun boundDocUuid(key: SourceKey): UUID?

    /** The result text stored with the row of exactly this four-part key; null when no row or no result. */
    fun resultOf(key: SourceKey): String?

    /** Inserts the row with its [result] (JSON text); false when a concurrent call inserted the same key first. */
    fun insert(
        key: SourceKey,
        docUuid: UUID,
        result: String,
    ): Boolean
}

/** The outcome of [RecordSourceEvent.record]: the Doc UUID, whether a row was inserted, and the stored result text. */
data class RecordedResult(
    val docUuid: UUID,
    val inserted: Boolean,
    val result: String,
)

/**
 * Records an event in `source_event` and assigns or binds its Doc UUID in one `canonical` transaction of the bound
 * tenant (INV-REQ-1191 behavior 2, INV-ADR-259). Contract rules of `recordDocument`: a new source key gets a new Doc
 * UUID; a repeat of the same four-part key returns the original Doc UUID and inserts nothing; a higher revision of the
 * same (tenant, source_system, source_key) binds the same Doc UUID; a Doc UUID is never regenerated on retry.
 */
class RecordSourceEvent(
    private val store: SourceEventStore,
    private val transaction: TransactionRunner,
    private val newDocUuid: () -> UUID,
) {
    /**
     * A repeat of the same four-part key returns the result stored at the first record and does not call [onNew]
     * (INV-REQ-789 behavior 3). A stored row without a result is an error, never an invented answer.
     */
    fun record(
        key: SourceKey,
        onNew: (UUID) -> String,
    ): RecordedResult =
        transaction.inTransaction {
            store.lockSourceKey(key) // first statement, before any read of source_event
            store.docUuidOf(key)?.let { return@inTransaction stored(key, it) }
            val docUuid = store.boundDocUuid(key) ?: newDocUuid()
            val result = onNew(docUuid)
            if (store.insert(key, docUuid, result)) {
                RecordedResult(docUuid, inserted = true, result)
            } else {
                stored(key, checkNotNull(store.docUuidOf(key)) { "source_event row vanished" })
            }
        }

    private fun stored(
        key: SourceKey,
        docUuid: UUID,
    ): RecordedResult =
        RecordedResult(
            docUuid,
            inserted = false,
            checkNotNull(store.resultOf(key)) { "source_event row has no result" },
        )
}
