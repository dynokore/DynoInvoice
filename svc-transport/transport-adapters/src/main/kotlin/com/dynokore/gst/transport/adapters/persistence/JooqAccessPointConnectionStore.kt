package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.application.AccessPointConnectionStore
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.domain.AcknowledgementRoute
import com.dynokore.gst.transport.domain.ConnectionRouteState
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.util.UUID

/**
 * The route state of an AP connection: the `adapter_id` record of the bound tenant (ruling INV-Q-9678 A). The
 * `connectionRef` is compared by equality, case-sensitively; a ref of another tenant is not found, by the tenant
 * predicate and by row-level security. The route version is the column `route_version`, not `version`, which a
 * ceiling save raises (INV-REQ-1317).
 */
class JooqAccessPointConnectionStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : AccessPointConnectionStore {
    override fun load(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionRouteState? =
        runner.inTransaction {
            dsl
                .select(AP_CONNECTION.ACKNOWLEDGEMENT_ROUTE, AP_CONNECTION.ENTITY_REFS, AP_CONNECTION.ROUTE_VERSION)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(UUID.fromString(tenant.value)))
                .and(AP_CONNECTION.ADAPTER_ID.eq(connectionRef))
                .fetchOne()
                ?.let { row ->
                    ConnectionRouteState(
                        row.value1()?.let(AcknowledgementRoute::parse),
                        row.value2().orEmpty().map { EntityRef(checkNotNull(it)) },
                        checkNotNull(row.value3()),
                    )
                }
        }

    override fun save(
        tenant: TenantRef,
        connectionRef: String,
        state: ConnectionRouteState,
    ) {
        runner.inTransaction {
            val updated =
                dsl
                    .update(AP_CONNECTION)
                    .set(AP_CONNECTION.ACKNOWLEDGEMENT_ROUTE, state.route?.name)
                    .set(AP_CONNECTION.ENTITY_REFS, state.entityRefs.map { it.value }.toTypedArray())
                    .set(AP_CONNECTION.ROUTE_VERSION, state.version)
                    .where(AP_CONNECTION.TENANT_ID.eq(UUID.fromString(tenant.value)))
                    .and(AP_CONNECTION.ADAPTER_ID.eq(connectionRef))
                    .execute()
            if (updated != 1) throw ConnectionNotFound(connectionRef)
        }
    }
}
