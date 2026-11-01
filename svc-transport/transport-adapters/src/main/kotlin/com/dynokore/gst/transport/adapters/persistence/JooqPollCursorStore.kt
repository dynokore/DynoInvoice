package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApPollCursor.Companion.AP_POLL_CURSOR
import com.dynokore.gst.transport.application.PollCursorStore
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The poll position of each adapter (INV-Q-9684 A): platform data, one row per adapter, the later write wins. */
class JooqPollCursorStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val platform: PlatformTenantBinding,
    private val workloadPrincipal: String,
    private val clock: Clock,
) : PollCursorStore {
    override fun read(adapter: AdapterId): String? =
        platform.bindPlatform(workloadPrincipal) {
            runner.inTransaction {
                dsl
                    .select(AP_POLL_CURSOR.POSITION)
                    .from(AP_POLL_CURSOR)
                    .where(AP_POLL_CURSOR.ADAPTER_ID.eq(adapter.value))
                    .fetchOne(AP_POLL_CURSOR.POSITION)
            }
        }

    override fun write(
        adapter: AdapterId,
        cursor: String?,
    ) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        platform.bindPlatform(workloadPrincipal) {
            runner.inTransaction {
                dsl
                    .insertInto(AP_POLL_CURSOR)
                    .set(AP_POLL_CURSOR.ADAPTER_ID, adapter.value)
                    .set(AP_POLL_CURSOR.POSITION, cursor)
                    .set(AP_POLL_CURSOR.UPDATED_AT, now)
                    .onConflict(AP_POLL_CURSOR.ADAPTER_ID)
                    .doUpdate()
                    .set(AP_POLL_CURSOR.POSITION, cursor)
                    .set(AP_POLL_CURSOR.UPDATED_AT, now)
                    .execute()
            }
        }
    }
}
