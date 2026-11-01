package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AcknowledgementReceived
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import java.util.UUID

internal val KNOWN_DOC: UUID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
internal val OWNER = AttemptOwnerRef(TenantRef("t-1"), EntityRef("e-1"))

internal class FakeOwners : AttemptOwner {
    override fun ownerOf(doc: DocRef): AttemptOwnerRef? = if (doc == DocRef(KNOWN_DOC, 1)) OWNER else null
}

internal class CountingLedger : NotificationLedger {
    var calls = 0
    val rows = mutableSetOf<Pair<AdapterId, String>>()

    override fun record(
        adapter: AdapterId,
        apNotificationId: String,
    ): LedgerResult {
        calls++
        return if (rows.add(adapter to apNotificationId)) LedgerResult.RECORDED else LedgerResult.REPLAY
    }
}

internal class CountingEvents : AcknowledgementEvents {
    val published = mutableListOf<AcknowledgementReceived>()

    override fun publish(
        owner: AttemptOwnerRef,
        event: AcknowledgementReceived,
    ) {
        published += event
    }
}

internal class DirectUnitOfWork : NotificationUnitOfWork {
    override fun <T> run(block: () -> T): T = block()
}

internal open class Cursors : PollCursorStore {
    val values = mutableMapOf<AdapterId, String?>()
    var writes = 0

    override fun read(adapter: AdapterId): String? = values[adapter]

    override fun write(
        adapter: AdapterId,
        cursor: String?,
    ) {
        writes++
        values[adapter] = cursor
    }
}
