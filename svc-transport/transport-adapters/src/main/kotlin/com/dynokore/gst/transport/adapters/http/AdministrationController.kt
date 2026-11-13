package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.AccessPointConnectionStore
import com.dynokore.gst.transport.application.ClearAcknowledgementRoute
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.ElectAcknowledgementRoute
import com.dynokore.gst.transport.application.FailoverAdapter
import com.dynokore.gst.transport.application.SetConnectionEntities
import com.dynokore.gst.transport.application.TestConnection
import com.dynokore.gst.transport.contract.api.AdministrationApi
import com.dynokore.gst.transport.contract.model.AcknowledgementRouteResponse
import com.dynokore.gst.transport.contract.model.ConnectionTestResult
import com.dynokore.gst.transport.contract.model.ElectAcknowledgementRouteRequest
import com.dynokore.gst.transport.contract.model.FailoverAdapterRequest
import com.dynokore.gst.transport.contract.model.FailoverResult
import com.dynokore.gst.transport.contract.model.SetConnectionEntitiesRequest
import com.dynokore.gst.transport.contract.model.SetConnectionEntitiesResponse
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.transport.application.ConnectionTestResult as TestOutcome

/**
 * The `administration` operations of the transport contract (TPL-08). `electAcknowledgementRoute` and
 * `clearAcknowledgementRoute` (INV-REQ-1310; rulings INV-Q-9678 A, INV-Q-9680 A), `failoverAdapter` (INV-REQ-796),
 * `setConnectionEntities` and `testConnection` (INV-REQ-835, INV-REQ-836; INV-T-18675) are implemented here;
 * `rotateCredential` answers 501 until the task named for it in `operations.tsv` overrides its method.
 *
 * Both are end-user commands at tenant scope; [RouteCaller] decides the permission. The connection is named by
 * `connectionRef` = the `adapter_id` of the bound tenant's connection; a ref naming no record of that tenant is
 * 404 SRT-005 with nothing recorded or published. The refusals are mapped by [AdministrationAdvice].
 */
@RestController
@Suppress("LongParameterList") // one collaborator per operation of the interface, wired by the composition root
open class AdministrationController(
    private val elect: ElectAcknowledgementRoute,
    private val clear: ClearAcknowledgementRoute,
    private val connections: AccessPointConnectionStore,
    private val caller: RouteCaller,
    private val scope: TenantScope,
    private val failover: FailoverAdapter,
    private val setEntities: SetConnectionEntities,
    private val testConnection: TestConnection,
    private val commands: IdempotentCommands,
    private val clock: Clock,
) : AdministrationApi {
    /**
     * `failoverAdapter`: asks `adapter.failover`; the entity is `entityRef` of the request and the target
     * `targetAdapterRef`, never inferred. A target that is not a registered adapter is 404 SRT-005 with nothing
     * changed; a repeat for an entity already on the target is a no-op that answers 200 again.
     */
    override fun failoverAdapter(
        adapterRef: String,
        idempotencyKey: UUID,
        failoverAdapterRequest: FailoverAdapterRequest,
    ): ResponseEntity<FailoverResult> {
        val user = caller.require(RouteCaller.ADAPTER_FAILOVER_PERMISSION)
        if (failoverAdapterRequest.entityRef.isBlank()) throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        scope.bind(user.tenant, user.actor) {
            failover.failover(
                EntityRef(failoverAdapterRequest.entityRef),
                AdapterId(failoverAdapterRequest.targetAdapterRef),
            )
        }
        return ResponseEntity.ok(
            FailoverResult(
                adapterRef,
                failoverAdapterRequest.targetAdapterRef,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            ),
        )
    }

    /**
     * `setConnectionEntities`: asks `adapter.configure`. Under the `Idempotency-Key` (API-15) the replacement and the
     * key record commit together, so a repeat answers the original outcome and publishes once. SUB-018, SUB-019 and
     * a repeated ref are refused by the use case before anything is stored or published.
     */
    override fun setConnectionEntities(
        connectionRef: String,
        idempotencyKey: UUID,
        setConnectionEntitiesRequest: SetConnectionEntitiesRequest,
    ): ResponseEntity<SetConnectionEntitiesResponse> {
        val user = caller.require(RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val tenant = TenantRef(user.tenant.value.toString())
        val refs = setConnectionEntitiesRequest.entityRefs.toList()
        return scope.bind(user.tenant, user.actor) {
            commands.once(
                user.actor,
                idempotencyKey,
                "PUT setConnectionEntities $connectionRef ${refs.sorted()}",
                SetConnectionEntitiesResponse::class.java,
            ) {
                setEntities.set(tenant, connectionRef, refs)
                val state = connections.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)
                SetConnectionEntitiesResponse(state.entityRefs.map { it.value }.toSet(), state.version.toInt())
            }
        }
    }

    /**
     * `testConnection`: asks `adapter.configure`, then runs the probe through the use case: `resultClass` is the class
     * of the probe, `detail` a code only and `state` the breaker state after the outcome. A ref naming no record of the
     * tenant is 404 SRT-005 before the AP is called.
     */
    override fun testConnection(
        adapterRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<ConnectionTestResult> {
        val user = caller.require(RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val tenant = TenantRef(user.tenant.value.toString())
        val result =
            scope.bind(user.tenant, user.actor) {
                connections.load(tenant, adapterRef) ?: throw ConnectionNotFound(adapterRef)
                testConnection.test(tenant, adapterRef)
            }
        return ResponseEntity.ok(result.toResponse())
    }

    private fun TestOutcome.toResponse() =
        ConnectionTestResult(
            adapterRef = connectionRef,
            state = ConnectionTestResult.State.valueOf(state.name),
            resultClass = ConnectionTestResult.ResultClass.valueOf(classification.name),
            detail = detail,
            checkedAt = OffsetDateTime.ofInstant(checkedAt, ZoneOffset.UTC),
        )

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
