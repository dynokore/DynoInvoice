package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.AccessPointConnectionStore
import com.dynokore.gst.transport.application.ClearAcknowledgementRoute
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.ElectAcknowledgementRoute
import com.dynokore.gst.transport.contract.api.AdministrationApi
import com.dynokore.gst.transport.contract.model.AcknowledgementRouteResponse
import com.dynokore.gst.transport.contract.model.ElectAcknowledgementRouteRequest
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.TenantScope
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The `administration` operations of the transport contract (TPL-08). `electAcknowledgementRoute` and
 * `clearAcknowledgementRoute` (INV-REQ-1310; rulings INV-Q-9678 A, INV-Q-9680 A) are implemented here; the others
 * answer 501 until the task named for each in `operations.tsv` overrides its method.
 *
 * Both are end-user commands at tenant scope; [RouteCaller] decides the permission. The connection is named by
 * `connectionRef` = the `adapter_id` of the bound tenant's connection; a ref naming no record of that tenant is
 * 404 SRT-005 with nothing recorded or published. The refusals are mapped by [AdministrationAdvice].
 */
@RestController
open class AdministrationController(
    private val elect: ElectAcknowledgementRoute,
    private val clear: ClearAcknowledgementRoute,
    private val connections: AccessPointConnectionStore,
    private val caller: RouteCaller,
    private val scope: TenantScope,
) : AdministrationApi {
    override fun electAcknowledgementRoute(
        connectionRef: String,
        idempotencyKey: UUID,
        electAcknowledgementRouteRequest: ElectAcknowledgementRouteRequest,
    ): ResponseEntity<AcknowledgementRouteResponse> =
        command(connectionRef) { tenant ->
            elect.elect(tenant, connectionRef, electAcknowledgementRouteRequest.acknowledgementRoute.value)
        }

    override fun clearAcknowledgementRoute(
        connectionRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<AcknowledgementRouteResponse> =
        command(connectionRef) { tenant -> clear.clear(tenant, connectionRef) }

    private fun command(
        connectionRef: String,
        change: (TenantRef) -> Unit,
    ): ResponseEntity<AcknowledgementRouteResponse> {
        val user = caller.require()
        val tenant = TenantRef(user.tenant.value.toString())
        val state =
            scope.bind(user.tenant, user.actor) {
                change(tenant)
                connections.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)
            }
        return ResponseEntity.ok(
            AcknowledgementRouteResponse(
                entityRefs = state.entityRefs.map { it.value }.toSet(),
                version = state.version.toInt(),
                acknowledgementRoute =
                    state.route?.let {
                        AcknowledgementRouteResponse.AcknowledgementRoute.valueOf(
                            it.name,
                        )
                    },
            ),
        )
    }
}
