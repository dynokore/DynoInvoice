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
    val owners = mutableListOf<AttemptOwnerRef>()
    val kinds = mutableListOf<LedgerKind>()

    override fun record(
        owner: AttemptOwnerRef,
        adapter: AdapterId,
        apNotificationId: String,
        kind: LedgerKind,
    ): LedgerResult {
        calls++
        owners += owner
        kinds += kind
        return if (rows.add(adapter to apNotificationId)) LedgerResult.RECORDED else LedgerResult.REPLAY
    }
}

internal class CountingEvents : AcknowledgementEvents {
    val published = mutableListOf<AcknowledgementReceived>()
    val publishOwners = mutableListOf<AttemptOwnerRef>()

    override fun publish(
        owner: AttemptOwnerRef,
        event: AcknowledgementReceived,
    ) {
        published += event
        publishOwners += owner
    }
}

internal class DirectUnitOfWork : NotificationUnitOfWork {
    val owners = mutableListOf<AttemptOwnerRef>()

    override fun <T> run(
        owner: AttemptOwnerRef,
        block: () -> T,
    ): T {
        owners += owner
        return block()
    }
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
