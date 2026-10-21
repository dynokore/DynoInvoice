package com.dynokore.gst.canonical.application.source

import com.dynokore.gst.canonical.domain.source.RecordedEvent
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** Port of the `source_event` table (canonical schema). */
interface SourceEventStore {
    /** The Doc UUID of the row with exactly this four-part key, if any. */
    fun docUuidOf(key: SourceKey): UUID?

    /** The Doc UUID bound to any revision of (tenant, source_system, source_key), if any. */
    fun boundDocUuid(key: SourceKey): UUID?

    /** Inserts the row; false when a concurrent call inserted the same four-part key first. */
    fun insert(
        key: SourceKey,
        docUuid: UUID,
    ): Boolean
}

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
    fun record(key: SourceKey): RecordedEvent =
        transaction.inTransaction {
            store.docUuidOf(key)?.let { return@inTransaction RecordedEvent(it, inserted = false) }
            val docUuid = store.boundDocUuid(key) ?: newDocUuid()
            if (store.insert(key, docUuid)) {
                RecordedEvent(docUuid, inserted = true)
            } else {
                RecordedEvent(checkNotNull(store.docUuidOf(key)) { "source_event row vanished" }, inserted = false)
            }
        }
}
