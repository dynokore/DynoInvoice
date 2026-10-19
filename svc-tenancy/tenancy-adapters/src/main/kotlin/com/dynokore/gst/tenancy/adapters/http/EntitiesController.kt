package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.structure.GroupKind
import com.dynokore.gst.tenancy.application.structure.NewBusinessUnit
import com.dynokore.gst.tenancy.application.structure.NewEntity
import com.dynokore.gst.tenancy.application.structure.NewGroup
import com.dynokore.gst.tenancy.application.structure.StructureCommands
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.application.structure.StructureOutcome
import com.dynokore.gst.tenancy.application.structure.StructureQueries
import com.dynokore.gst.tenancy.application.structure.StructureView
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.application.structure.BusinessUnitRecord
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.GroupRecord
import com.dynokore.gst.tenancy.contract.api.EntitiesApi
import com.dynokore.gst.tenancy.contract.model.BusinessUnitResponse
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.MoveBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.RegisterBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.StructureNode
import com.dynokore.gst.tenancy.contract.model.UpdateBusinessUnitRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. The structure operations of the tenancy contract that this service implements in INV-T-4925: createGroup,
 * createEntity, registerBusinessUnit, moveBusinessUnit, updateBusinessUnit and getEntityStructure (scope tenant). The
 * request's `tenantRef` must name the bound tenant, else the tenant is absent from the caller's scope (404).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class EntitiesController(
    private val commands: StructureCommands,
    private val queries: StructureQueries,
    private val tenants: TenantContext,
) : EntitiesApi {
    override fun createGroup(
        idempotencyKey: UUID,
        createGroupRequest: CreateGroupRequest,
    ): ResponseEntity<GroupResponse> {
        val tenant = boundTenant(createGroupRequest.tenantRef)
        val request =
            NewGroup(createGroupRequest.name, GroupKind.valueOf(createGroupRequest.groupKind.name), createGroupRequest.gstn)
        val group = respond(commands.createGroup(tenant, createGroupRequest.tenantRef, request))
        return ResponseEntity.status(HttpStatus.CREATED).body(groupResponse(group, createGroupRequest.tenantRef))
    }

    override fun createEntity(
        idempotencyKey: UUID,
        createEntityRequest: CreateEntityRequest,
    ): ResponseEntity<EntityResponse> {
        val tenant = boundTenant(createEntityRequest.tenantRef)
        val request =
            NewEntity(
                createEntityRequest.groupRef,
                createEntityRequest.legalName,
                createEntityRequest.constitution.value,
                createEntityRequest.uen,
                createEntityRequest.gstn,
                createEntityRequest.code,
            )
        val entity = respond(commands.createEntity(tenant, request))
        return ResponseEntity.status(HttpStatus.CREATED).body(entityResponse(entity))
    }

    override fun registerBusinessUnit(
        idempotencyKey: UUID,
        registerBusinessUnitRequest: RegisterBusinessUnitRequest,
    ): ResponseEntity<BusinessUnitResponse> {
        val tenant = boundTenant(registerBusinessUnitRequest.tenantRef)
        val request =
            NewBusinessUnit(
                registerBusinessUnitRequest.entityRef,
                registerBusinessUnitRequest.name,
                registerBusinessUnitRequest.code,
            )
        val unit = respond(commands.registerBusinessUnit(tenant, request))
        return ResponseEntity.status(HttpStatus.CREATED).body(unitResponse(unit, registerBusinessUnitRequest.tenantRef))
    }

    override fun moveBusinessUnit(
        businessUnitRef: String,
        idempotencyKey: UUID,
        moveBusinessUnitRequest: MoveBusinessUnitRequest,
    ): ResponseEntity<BusinessUnitResponse> {
        val unit = respond(commands.moveBusinessUnit(tenants.current(), businessUnitRef, moveBusinessUnitRequest.toEntityRef))
        return ResponseEntity.ok(unitResponse(unit, tenantRef()))
    }

    override fun updateBusinessUnit(
        businessUnitRef: String,
        idempotencyKey: UUID,
        updateBusinessUnitRequest: UpdateBusinessUnitRequest,
    ): ResponseEntity<BusinessUnitResponse> {
        val unit =
            respond(
                commands.updateBusinessUnit(
                    tenants.current(),
                    businessUnitRef,
                    updateBusinessUnitRequest.name,
                    updateBusinessUnitRequest.code,
                ),
            )
        return ResponseEntity.ok(unitResponse(unit, tenantRef()))
    }

    override fun getEntityStructure(entityRef: String): ResponseEntity<EntityResponse> {
        val view = queries.node(entityRef) ?: notFound()
        return ResponseEntity.ok(viewResponse(view))
    }

    private fun boundTenant(tenantRef: String): TenantId {
        val id = runCatching { TenantRefs.idOf(tenantRef) }.getOrElse { invalid("tenantRef is not a usable tenant reference") }
        if (id != tenants.current()) notFound()
        return id
    }

    private fun tenantRef(): String = queries.tenantRef() ?: notFound()

    private fun <T> respond(outcome: StructureOutcome<T>): T =
        when (outcome) {
            is StructureOutcome.Done -> outcome.value
            StructureOutcome.NotFound -> notFound()
            is StructureOutcome.Conflict -> conflict("the short code is in use or fixed by an aggregate number")
            is StructureOutcome.Invalid -> invalid("the short code must be 1 to 10 characters of A-Z and 0-9")
        }

    private fun groupResponse(
        group: GroupRecord,
        tenantRef: String,
    ) = GroupResponse(
        group.ref,
        tenantRef,
        group.name,
        GroupResponse.GroupKind.valueOf(group.kind.name),
        group.gstn,
    )

    private fun unitResponse(
        unit: BusinessUnitRecord,
        tenantRef: String,
    ) = BusinessUnitResponse(unit.ref, tenantRef, unit.entityRef, unit.name, unit.code)

    private fun entityResponse(entity: EntityRecord) =
        EntityResponse(
            entity.ref,
            entity.legalName,
            EntityResponse.LifecycleState.valueOf(entity.lifecycleState),
            nodeOf(entityNode(entity)),
        )

    private fun viewResponse(view: StructureView) =
        EntityResponse(view.ref, view.name, EntityResponse.LifecycleState.valueOf(view.lifecycleState), nodeOf(view.node))

    private fun nodeOf(node: StructureNodeState) =
        StructureNode(
            StructureNode.Kind.valueOf(node.kind.name),
            node.retired,
            node.name,
            emptyList(),
            node.structureVersion,
            node.parentRef,
            node.code,
            node.uen,
            node.gstn,
        )
}
