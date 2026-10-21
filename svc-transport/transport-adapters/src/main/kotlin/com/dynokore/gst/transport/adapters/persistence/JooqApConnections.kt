package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.application.ApConnections
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ThroughputCeiling
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/** The rate fields of the AP connection record of the bound tenant (INV-REQ-1317). */
class JooqApConnections(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ApConnections {
    override fun ceiling(adapter: AdapterId): ThroughputCeiling? =
        runner.inTransaction {
            dsl
                .select(AP_CONNECTION.AGREED_RATE, AP_CONNECTION.TENANT_CEILING)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.ADAPTER_ID.eq(adapter.value))
                .fetchOne()
                ?.let { ThroughputCeiling.restore(checkNotNull(it.value1()), it.value2()) }
        }

    override fun saveCeiling(
        adapter: AdapterId,
        ceiling: ThroughputCeiling,
    ) {
        runner.inTransaction {
            dsl
                .update(AP_CONNECTION)
                .set(AP_CONNECTION.TENANT_CEILING, ceiling.tenantValue)
                .set(AP_CONNECTION.VERSION, AP_CONNECTION.VERSION.plus(1))
                .where(AP_CONNECTION.ADAPTER_ID.eq(adapter.value))
                .execute()
        }
    }
}
