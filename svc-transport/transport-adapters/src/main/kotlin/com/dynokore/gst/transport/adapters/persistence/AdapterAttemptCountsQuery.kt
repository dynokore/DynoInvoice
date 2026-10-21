package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AdapterAttemptCounts.Companion.ADAPTER_ATTEMPT_COUNTS
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset

/** The finished attempts of one adapter since a time, across tenants: counts only, through the sys-owned function. */
internal object AdapterAttemptCountsQuery {
    @Suppress("LongParameterList")
    fun counts(
        dsl: DSLContext,
        runner: TransactionRunner,
        platform: PlatformTenantBinding,
        actor: String,
        adapter: AdapterId,
        since: Instant,
    ): AttemptCounts =
        platform.bindPlatform(actor) {
            runner.inTransaction {
                val counts = ADAPTER_ATTEMPT_COUNTS.call(adapter.value, since.atOffset(ZoneOffset.UTC))
                val row =
                    checkNotNull(dsl.select(counts.TOTAL, counts.FAILED).from(counts).fetchOne()) { "no count row" }
                AttemptCounts(row.value1() ?: 0L, row.value2() ?: 0L)
            }
        }
}
