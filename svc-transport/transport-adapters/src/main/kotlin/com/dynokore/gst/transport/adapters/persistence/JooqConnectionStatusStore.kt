package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.ConnectionState
import com.dynokore.gst.transport.application.ConnectionStatusStore
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.util.UUID

/** The stored health state of the AP connection of the bound tenant (INV-REQ-836): the column `connection_state`. */
class JooqConnectionStatusStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ConnectionStatusStore {
    override fun stored(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionState? =
        runner.inTransaction {
            dsl
                .select(AP_CONNECTION.CONNECTION_STATE)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(UUID.fromString(tenant.value)))
                .and(AP_CONNECTION.ADAPTER_ID.eq(connectionRef))
                .fetchOne(AP_CONNECTION.CONNECTION_STATE)
                ?.let(ConnectionState::valueOf)
        }

    override fun save(
        tenant: TenantRef,
        connectionRef: String,
        state: ConnectionState,
    ) {
        runner.inTransaction {
            val updated =
                dsl
                    .update(AP_CONNECTION)
                    .set(AP_CONNECTION.CONNECTION_STATE, state.name)
                    .where(AP_CONNECTION.TENANT_ID.eq(UUID.fromString(tenant.value)))
                    .and(AP_CONNECTION.ADAPTER_ID.eq(connectionRef))
                    .execute()
            if (updated != 1) throw ConnectionNotFound(connectionRef)
        }
    }
}
