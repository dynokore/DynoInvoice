package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.WindowDocument
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate
import java.util.UUID

/** Port of the `document` and `document_version` tables (canonical schema). */
interface DocumentStore {
    /** Inserts the document and its version 1 with `current_version` 1. */
    fun insert(document: NewDocument)

    /**
     * Inserts version [expectedVersion] + 1 and moves `current_version` from [expectedVersion] to it, when the pointer
     * still holds [expectedVersion]; [UpdateOutcome.Conflict] and no write otherwise (also for a document the bound
     * tenant does not see). Sets `transaction_date` to that of [version]; `partition_date` is not touched.
     */
    fun createVersion(
        docUuid: UUID,
        expectedVersion: Int,
        version: NewVersion,
    ): UpdateOutcome

    /** At most [pageSize] documents of the entity whose current transaction date is in the window, by doc_uuid. */
    @Suppress("LongParameterList") // the signature of the window read of INV-REQ-772 b1
    fun readWindow(
        tenantId: UUID,
        entityId: UUID,
        from: LocalDate,
        to: LocalDate,
        pageSize: Int,
        afterDocUuid: UUID?,
    ): List<WindowDocument>
}

/** Creates a version under optimistic concurrency on the document's current-version pointer (INV-REQ-2574). */
class CreateDocumentVersion(
    private val store: DocumentStore,
    private val transaction: TransactionRunner,
) {
    fun createVersion(
        docUuid: UUID,
        expectedVersion: Int,
        version: NewVersion,
    ): UpdateOutcome {
        require(expectedVersion >= 1) { "expectedVersion is below 1" }
        return transaction.inTransaction { store.createVersion(docUuid, expectedVersion, version) }
    }
}

/** The window read of INV-REQ-772 b1: documents by current transaction date, paged by doc_uuid. */
class WindowReader(
    private val store: DocumentStore,
    private val transaction: TransactionRunner,
) {
    @Suppress("LongParameterList") // the signature of the window read of INV-REQ-772 b1
    fun readWindow(
        tenantId: UUID,
        entityId: UUID,
        from: LocalDate,
        to: LocalDate,
        pageSize: Int,
        afterDocUuid: UUID?,
    ): List<WindowDocument> {
        require(pageSize in 1..MAX_PAGE) { "pageSize is outside 1..$MAX_PAGE" }
        require(!to.isBefore(from)) { "the window ends before it starts" }
        return transaction.inTransaction { store.readWindow(tenantId, entityId, from, to, pageSize, afterDocUuid) }
    }

    private companion object {
        const val MAX_PAGE = 1000
    }
}
