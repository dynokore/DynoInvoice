package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AcknowledgementReceived
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ApNotification
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef

/** The tenant and entity of the attempt row of a Doc UUID and version. */
data class AttemptOwnerRef(
    val tenant: TenantRef,
    val entity: EntityRef,
)

/** Doc UUID and version to tenant and entity, or none when no attempt row exists (INV-Q-9674 A). */
interface AttemptOwner {
    fun ownerOf(doc: DocRef): AttemptOwnerRef?
}

enum class LedgerResult { RECORDED, REPLAY }

/** The de-duplication ledger: one row per adapter and AP notification id (INV-AC-623). */
interface NotificationLedger {
    fun record(
        adapter: AdapterId,
        apNotificationId: String,
    ): LedgerResult
}

/** The one place an `AcknowledgementReceived` event is written; called in the unit of work of the ledger row. */
interface AcknowledgementEvents {
    fun publish(
        owner: AttemptOwnerRef,
        event: AcknowledgementReceived,
    )
}

/** The unit of work the ledger row and the event share: both commit, or neither does. */
interface NotificationUnitOfWork {
    fun <T> run(block: () -> T): T
}

/** One page of notifications of an adapter after a cursor; [nextCursor] is what the next run reads from. */
data class NotificationPage(
    val items: List<ApNotification>,
    val nextCursor: String?,
)

interface NotificationSource {
    fun pageAfter(
        adapter: AdapterId,
        cursor: String?,
    ): NotificationPage
}

interface PollCursorStore {
    fun read(adapter: AdapterId): String?

    fun write(
        adapter: AdapterId,
        cursor: String?,
    )
}
