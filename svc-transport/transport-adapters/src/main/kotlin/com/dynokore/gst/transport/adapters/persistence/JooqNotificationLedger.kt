package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApNotificationLedger.Companion.AP_NOTIFICATION_LEDGER
import com.dynokore.gst.transport.application.AttemptOwnerRef
import com.dynokore.gst.transport.application.LedgerKind
import com.dynokore.gst.transport.application.LedgerResult
import com.dynokore.gst.transport.application.NotificationLedger
import com.dynokore.gst.transport.domain.AdapterId
import org.jooq.DSLContext
import java.util.UUID

/**
 * The de-duplication ledger (INV-AC-623): one row per adapter and AP notification id, written with the tenant and
 * entity of the owner it is given. It writes in the transaction and tenant binding of the caller (the unit of work);
 * with no tenant bound, row level security refuses the insert.
 */
class JooqNotificationLedger(
    private val dsl: DSLContext,
) : NotificationLedger {
    override fun record(
        owner: AttemptOwnerRef,
        adapter: AdapterId,
        apNotificationId: String,
        kind: LedgerKind,
    ): LedgerResult {
        val inserted =
            dsl
                .insertInto(AP_NOTIFICATION_LEDGER)
                .set(AP_NOTIFICATION_LEDGER.ADAPTER_ID, adapter.value)
                .set(AP_NOTIFICATION_LEDGER.AP_NOTIFICATION_ID, apNotificationId)
                .set(AP_NOTIFICATION_LEDGER.TENANT_ID, UUID.fromString(owner.tenant.value))
                .set(AP_NOTIFICATION_LEDGER.ENTITY_REF, owner.entity.value)
                .set(AP_NOTIFICATION_LEDGER.KIND, kind.name)
                .onConflict(AP_NOTIFICATION_LEDGER.ADAPTER_ID, AP_NOTIFICATION_LEDGER.AP_NOTIFICATION_ID)
                .doNothing()
                .execute()
        return if (inserted == 1) LedgerResult.RECORDED else LedgerResult.REPLAY
    }
}
